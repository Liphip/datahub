package com.dataturntable.connectors.polling

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorLeaseService
import com.dataturntable.connectors.ConnectorMetrics
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.security.EncryptionUtils
import com.dataturntable.service.KafkaService
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ConnectorPollingService schedules periodic fetching for INPUT connectors that have
 * pollingEnabled=true. Only REST and FILE connectors support polling.
 *
 * Push-based connectors (MQTT, WebSocket, UDP) never poll — they receive data via their
 * respective connection managers and must not have pollingEnabled=true.
 *
 * On each poll tick:
 *  1. Fetch data from the connector via ConnectorRuntimeService.fetchFromConnector()
 *  2. Find all active pipelines that use this connector on an IMPORT node
 *  3. Create a PipelineRun for each and trigger via Kafka with the fetched data
 */
@ApplicationScoped
class ConnectorPollingService {

    private val LOG = Logger.getLogger(ConnectorPollingService::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var connectorRuntime: ConnectorRuntimeService

    @Inject
    lateinit var kafkaService: KafkaService

    @Inject
    lateinit var pollExecutor: PollTransactionExecutor

    @Inject
    lateinit var connectorMetrics: ConnectorMetrics

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var connectorLeaseService: ConnectorLeaseService

    @ConfigProperty(name = "dataturntable.connectors.lease.reconcile-interval-seconds", defaultValue = "20")
    var leaseReconcileIntervalSeconds: Long = 20

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val scheduledTasks = ConcurrentHashMap<UUID, ScheduledFuture<*>>()
    private val connectorIntervals = ConcurrentHashMap<UUID, Int>()
    private var reconcileTask: ScheduledFuture<*>? = null

    // ── Poll run tracking (populated by executePoll wrapper) ─────────────────
    private val lastRunTimes    = ConcurrentHashMap<UUID, Instant>()
    private val lastRunSuccesses = ConcurrentHashMap<UUID, Boolean>()
    private val lastRunErrors   = ConcurrentHashMap<UUID, String>()

    /** Connector types that support polling. Push-based connectors are excluded. */
    val POLLABLE_TYPES = setOf(Connector.ConnectorType.REST, Connector.ConnectorType.FILE)

    // ── Status info ───────────────────────────────────────────────────────────

    data class PollingInfo(
        val scheduled: Boolean,
        val intervalSeconds: Int?,
        val lastRunAt: Instant?,
        val lastRunSuccess: Boolean?,
        val lastRunError: String?,
    )

    fun getPollingInfo(connectorId: UUID): PollingInfo {
        val task = scheduledTasks[connectorId]
        return PollingInfo(
            scheduled = task != null && !task.isDone && !task.isCancelled,
            intervalSeconds = connectorIntervals[connectorId],
            lastRunAt = lastRunTimes[connectorId],
            lastRunSuccess = lastRunSuccesses[connectorId],
            lastRunError = lastRunErrors[connectorId],
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onStart(@Observes event: StartupEvent) {
        if (!runtimeRoleConfig.ownsIngestRuntime()) {
            LOG.infof("ConnectorPollingService startup skipped for runtime role %s", runtimeRoleConfig.role)
            return
        }
        LOG.info("ConnectorPollingService starting — scheduling poll connectors")
        scheduleAll()
        reconcileTask?.cancel(false)
        reconcileTask = scheduler.scheduleAtFixedRate(
            { scheduleAll() },
            leaseReconcileIntervalSeconds.coerceAtLeast(5),
            leaseReconcileIntervalSeconds.coerceAtLeast(5),
            TimeUnit.SECONDS
        )
    }

    fun onStop(@Observes event: ShutdownEvent) {
        if (!runtimeRoleConfig.ownsIngestRuntime()) return
        LOG.info("ConnectorPollingService shutting down")
        reconcileTask?.cancel(false)
        scheduledTasks.keys.toList().forEach { cancelConnector(it) }
        scheduler.shutdown()
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule or reschedule a specific connector after create/update.
     * If the connector is not pollable or pollingEnabled=false, cancels any existing schedule.
     */
    fun onConnectorChanged(connectorId: UUID) {
        try {
            val connector = em.find(Connector::class.java, connectorId) ?: run {
                cancelConnector(connectorId)
                return
            }
            if (connector.connectorType !in POLLABLE_TYPES ||
                connector.direction != Connector.Direction.INPUT ||
                !connector.isActive ||
                !connector.pollingEnabled) {
                cancelConnector(connectorId)
            } else if (!connectorLeaseService.tryAcquireOrRenew(connectorId)) {
                cancelConnector(connectorId, releaseLease = false)
            } else {
                scheduleConnector(connector)
            }
        } catch (e: Exception) {
            LOG.errorf("onConnectorChanged failed for %s: %s", connectorId, e.message)
        }
    }

    /**
     * Cancel polling for a connector that was deleted or deactivated.
     */
    fun cancelConnector(connectorId: UUID, releaseLease: Boolean = true) {
        scheduledTasks.remove(connectorId)?.cancel(false)
        connectorIntervals.remove(connectorId)
        LOG.infof("Cancelled polling for connector %s", connectorId)
        if (releaseLease) {
            connectorLeaseService.release(connectorId)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun scheduleAll() {
        val connectors = em.createQuery(
            """SELECT c FROM Connector c
               WHERE c.pollingEnabled = true
               AND c.direction = :dir
               AND c.isActive = true""",
            Connector::class.java
        ).setParameter("dir", Connector.Direction.INPUT).resultList

        val ownedConnectorIds = mutableSetOf<UUID>()
        for (connector in connectors) {
            if (connector.connectorType !in POLLABLE_TYPES) continue
            try {
                val connectorId = connector.id ?: continue
                if (!connectorLeaseService.tryAcquireOrRenew(connectorId)) {
                    cancelConnector(connectorId, releaseLease = false)
                    continue
                }
                ownedConnectorIds += connectorId
                scheduleConnector(connector)
            } catch (e: Exception) {
                LOG.errorf("Failed to schedule connector %s: %s", connector.id, e.message)
            }
        }
        scheduledTasks.keys.filter { it !in ownedConnectorIds }.forEach { cancelConnector(it) }
        LOG.infof("ConnectorPollingService: scheduled %d poll connector(s)",
            ownedConnectorIds.size)
    }

    private fun scheduleConnector(connector: Connector) {
        val connectorId = connector.id ?: return
        val intervalSec = connector.pollingIntervalSeconds.coerceAtLeast(5).toLong()

        // Cancel existing schedule
        scheduledTasks.remove(connectorId)?.cancel(false)

        val future = scheduler.scheduleAtFixedRate(
            { executePoll(connectorId) },
            intervalSec, // initial delay = one interval (avoid thundering herd on startup)
            intervalSec,
            TimeUnit.SECONDS
        )
        scheduledTasks[connectorId] = future
        connectorIntervals[connectorId] = connector.pollingIntervalSeconds.coerceAtLeast(5)
        LOG.infof("Scheduled polling for connector %s (%s) every %ds",
            connectorId, connector.name, intervalSec)
    }

    private fun executePoll(connectorId: UUID) {
        try {
            pollExecutor.executePoll(connectorId)
            lastRunTimes[connectorId] = Instant.now()
            lastRunSuccesses[connectorId] = true
            lastRunErrors.remove(connectorId)
            connectorMetrics.pollCounter("success").increment()
        } catch (e: Exception) {
            lastRunTimes[connectorId] = Instant.now()
            lastRunSuccesses[connectorId] = false
            lastRunErrors[connectorId] = e.message ?: "Unknown error"
            connectorMetrics.pollCounter("failure").increment()
            LOG.errorf("Poll execution failed for connector %s: %s", connectorId, e.message)
        }
    }
}

/**
 * Separate transactional bean for poll execution so it can be called via CDI proxy
 * from the ScheduledExecutorService thread.
 */
@ApplicationScoped
class PollTransactionExecutor {

    private val LOG = Logger.getLogger(PollTransactionExecutor::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var connectorRuntime: ConnectorRuntimeService

    @Inject
    lateinit var kafkaService: KafkaService

    @PersistenceContext
    lateinit var em: EntityManager

    @Transactional
    fun executePoll(connectorId: UUID) {
        val connector = em.find(Connector::class.java, connectorId) ?: return
        if (!connector.isActive || !connector.pollingEnabled) return

        val decryptedConfig = connectorRuntime.decryptConfig(connector)

        // Fetch data from the connector — propagate errors so the wrapper records failure
        val data = try {
            connectorRuntime.fetchFromConnector(connector, decryptedConfig)
        } catch (e: Exception) {
            LOG.errorf("Poll fetch failed for connector %s (%s): %s", connectorId, connector.name, e.message)
            throw RuntimeException("Fetch failed for ${connector.name}: ${e.message}", e)
        }

        if (data == null) {
            LOG.debugf("Connector %s returned null data on poll — skipping trigger", connectorId)
            return
        }

        // Find all active pipelines using this connector on an IMPORT node
        val pipelines = findPipelinesUsingConnector(connectorId)
        if (pipelines.isEmpty()) {
            LOG.debugf("No active pipelines use connector %s — skipping trigger", connectorId)
            return
        }

        for (pipelineId in pipelines) {
            val pipeline = em.find(Pipeline::class.java, pipelineId) ?: continue
            if (!pipeline.isActive) continue

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.triggerType = TriggerType.SCHEDULED
                this.status = PipelineRunStatus.PENDING
                this.startedAt = java.time.Instant.now()
            }
            em.persist(run)
            em.flush()

            kafkaService.triggerPipelineWithData(
                pipelineId = pipeline.id!!.toString(),
                runId = run.id!!.toString(),
                triggerType = TriggerType.SCHEDULED,
                data = mapOf(
                    "_connectorId" to connectorId.toString(),
                    "_triggerType" to "POLL",
                    "payload" to data
                )
            )
            LOG.infof("Poll connector %s triggered pipeline %s (run %s)", connectorId, pipeline.id, run.id)
        }
    }

    private fun findPipelinesUsingConnector(connectorId: UUID): List<UUID> {
        val activePipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.isActive = true",
            Pipeline::class.java
        ).resultList

        return activePipelines.mapNotNull { pipeline ->
            val graphDef = pipeline.graphDefinition ?: return@mapNotNull null
            val graph = try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(graphDef, Map::class.java) as? Map<String, Any>
            } catch (_: Exception) { null } ?: return@mapNotNull null
            val nodes = graph["nodes"] as? List<*> ?: return@mapNotNull null
            val hasNode = nodes.any { node ->
                val nodeMap = node as? Map<*, *> ?: return@any false
                val type = nodeMap["type"]?.toString()?.uppercase()
                val cId = nodeMap["connectorId"]?.toString()
                type == "IMPORT" && cId == connectorId.toString()
            }
            if (hasNode) pipeline.id else null
        }
    }
}
