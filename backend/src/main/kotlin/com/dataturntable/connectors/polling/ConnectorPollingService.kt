package com.dataturntable.connectors.polling

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorLeaseService
import com.dataturntable.connectors.ConnectorMetrics
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.connectors.inbox.INBOX_TYPES
import com.dataturntable.connectors.inbox.InboxPoller
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.security.EncryptionUtils
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.engine.PipelineRunQueue
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
 * pollingEnabled=true. REST connectors and inboxes (FILE, FTP, SFTP, EMAIL) support polling.
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
/**
 * Defines the class `ConnectorPollingService`, the contract implemented by this source unit.
 */
class ConnectorPollingService {

    private val LOG = Logger.getLogger(ConnectorPollingService::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var connectorRuntime: ConnectorRuntimeService

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

    @Inject
    lateinit var inboxPoller: InboxPoller

    @ConfigProperty(name = "dataturntable.connectors.lease.reconcile-interval-seconds", defaultValue = "10")
    var leaseReconcileIntervalSeconds: Long = 20

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val scheduledTasks = ConcurrentHashMap<UUID, ScheduledFuture<*>>()
    private val connectorIntervals = ConcurrentHashMap<UUID, Int>()
    private val connectorTypes = ConcurrentHashMap<UUID, Connector.ConnectorType>()
    private var reconcileTask: ScheduledFuture<*>? = null

    // ── Poll run tracking (populated by executePoll wrapper) ─────────────────
    private val lastRunTimes    = ConcurrentHashMap<UUID, Instant>()
    private val lastRunSuccesses = ConcurrentHashMap<UUID, Boolean>()
    private val lastRunErrors   = ConcurrentHashMap<UUID, String>()

    /** Connector types that support polling. Push-based connectors are excluded. */
    val POLLABLE_TYPES = setOf(Connector.ConnectorType.REST) + INBOX_TYPES

    // ── Status info ───────────────────────────────────────────────────────────

    /**
     * Defines the class `PollingInfo`, the contract implemented by this source unit.
     */
    data class PollingInfo(
        val scheduled: Boolean,
        val intervalSeconds: Int?,
        val lastRunAt: Instant?,
        val lastRunSuccess: Boolean?,
        val lastRunError: String?,
    )

    /**
     * Retrieves or enumerates the requested state.
     */
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

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
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

    /**
     * Implements `onStop` for this source unit.
     */
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
        if (!runtimeRoleConfig.ownsIngestRuntime()) return // connections live on ingest replicas only
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
        connectorTypes.remove(connectorId)
        LOG.infof("Cancelled polling for connector %s", connectorId)
        if (releaseLease) {
            connectorLeaseService.release(connectorId)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Implements `scheduleAll` for this source unit.
     */
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

    /**
     * Implements `scheduleConnector` for this source unit.
     */
    private fun scheduleConnector(connector: Connector) {
        val connectorId = connector.id ?: return
        val intervalSec = connector.pollingIntervalSeconds.coerceAtLeast(5).toLong()

        // Cancel existing schedule
        scheduledTasks.remove(connectorId)?.cancel(false)
        connectorTypes[connectorId] = connector.connectorType ?: return

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

    /**
     * Processes the named request, event, or operation.
     */
    private fun executePoll(connectorId: UUID) {
        try {
            // Inboxes run outside one long transaction: every item has its own ledger steps.
            if (connectorTypes[connectorId] in INBOX_TYPES) inboxPoller.poll(connectorId) else pollExecutor.executePoll(connectorId)
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
    lateinit var runQueue: PipelineRunQueue

    @Inject
    lateinit var directory: PipelineDirectory

    @PersistenceContext
    lateinit var em: EntityManager

    @Transactional
    /**
     * Processes the named request, event, or operation.
     */
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

            // With deduplicateBy on the IMPORT node, every record of the response is its own
            // run keyed by its fields: records the source returns again are not processed twice.
            val dedup = directory.get(pipelineId)?.deduplicateBy.orEmpty()
            val records = (data as? List<*>)?.takeIf { dedup.isNotEmpty() }
            if (records != null) {
                var queued = 0
                records.forEach { record ->
                    val key = PipelineDirectory.recordKey(record, dedup)
                    runQueue.enqueue(
                        pipelineId = pipeline.id!!,
                        triggerType = TriggerType.SCHEDULED,
                        data = mapOf("_connectorId" to connectorId.toString(), "_triggerType" to "POLL", "payload" to record),
                        partitionKey = "poll:$connectorId",
                        idempotencyKey = key?.let { "poll|$connectorId|$it" },
                    )
                    queued++
                }
                LOG.debugf("Poll connector %s queued %d records for pipeline %s", connectorId, queued, pipeline.id)
                continue
            }

            val runId = runQueue.enqueue(
                pipelineId = pipeline.id!!,
                triggerType = TriggerType.SCHEDULED,
                data = mapOf(
                    "_connectorId" to connectorId.toString(),
                    "_triggerType" to "POLL",
                    "payload" to data
                )
            )
            LOG.infof("Poll connector %s triggered pipeline %s (run %s)", connectorId, pipeline.id, runId)
        }
    }

    /**
     * Retrieves or enumerates the requested state.
     */
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
