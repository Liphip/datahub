package com.dataturntable.monitoring

import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorStatusService
import com.dataturntable.connectors.mqtt.MqttConnectionManager
import com.dataturntable.connectors.polling.ConnectorPollingService
import com.dataturntable.connectors.websocket.WebSocketConnectionManager
import com.dataturntable.pipeline.PipelineRunStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

data class MonitoringSnapshot(
    val collectedAt: Instant,
    val pipelineStates: Map<String, Long>,
    val recentPipelineStates: Map<String, Long>,
    val staleRunningCount: Long,
    val connectorHealth: Map<String, Long>,
    val connectorProtocols: Map<String, Long>,
    val connectorMessageFreshness: Map<String, Long>,
    val connectorPolling: Map<String, Long>,
)

@ApplicationScoped
class MonitoringSnapshotService {

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var connectorStatusService: ConnectorStatusService

    @Inject
    lateinit var mqttConnectionManager: MqttConnectionManager

    @Inject
    lateinit var webSocketConnectionManager: WebSocketConnectionManager

    @Inject
    lateinit var connectorPollingService: ConnectorPollingService

    @Transactional
    fun collect(now: Instant = Instant.now()): MonitoringSnapshot {
        val since24h = now.minus(24, ChronoUnit.HOURS)
        val staleCutoff = now.minus(30, ChronoUnit.MINUTES)

        @Suppress("UNCHECKED_CAST")
        val pipelineStateRows = em.createQuery(
            "SELECT r.status, COUNT(r) FROM PipelineRun r GROUP BY r.status"
        ).resultList as List<Array<Any>>

        @Suppress("UNCHECKED_CAST")
        val recentPipelineStateRows = em.createQuery(
            "SELECT r.status, COUNT(r) FROM PipelineRun r WHERE r.startedAt >= :since GROUP BY r.status"
        ).setParameter("since", since24h).resultList as List<Array<Any>>

        val staleRunningCount = em.createQuery(
            "SELECT COUNT(r) FROM PipelineRun r WHERE r.status = :status AND r.startedAt < :cutoff"
        )
            .setParameter("status", PipelineRunStatus.RUNNING)
            .setParameter("cutoff", staleCutoff)
            .singleResult as Long

        val connectors = em.createQuery("SELECT c FROM Connector c", Connector::class.java).resultList

        val connectorHealth = linkedMapOf<String, Long>()
        val connectorProtocols = linkedMapOf<String, Long>()
        val connectorMessageFreshness = linkedMapOf<String, Long>()
        val connectorPolling = linkedMapOf<String, Long>()

        connectors.forEach { connector ->
            val type = connector.connectorType?.name?.lowercase() ?: "unknown"
            val direction = connector.direction?.name?.lowercase() ?: "unknown"
            val health = summarizeHealth(connector)
            increment(connectorHealth, "connector_type=$type,direction=$direction,state=$health")

            when (connector.connectorType) {
                Connector.ConnectorType.MQTT -> {
                    val info = connector.id?.let { mqttConnectionManager.getClientInfo(it) }
                    val connectionState = if (info?.connected == true) "connected" else "disconnected"
                    increment(connectorProtocols, "protocol=mqtt,state=$connectionState")

                    if (connector.direction == Connector.Direction.INPUT) {
                        val freshnessState = when {
                            info?.lastMessageAt == null -> "idle"
                            info.lastMessageAt!!.isAfter(now.minus(15, ChronoUnit.MINUTES)) -> "fresh"
                            else -> "stale"
                        }
                        increment(connectorMessageFreshness, "protocol=mqtt,state=$freshnessState")
                    }
                }

                Connector.ConnectorType.WEBSOCKET -> {
                    val info = connector.id?.let { webSocketConnectionManager.getClientInfo(it) }
                    val connectionState = if (info?.connected == true) "connected" else "disconnected"
                    increment(connectorProtocols, "protocol=websocket,state=$connectionState")

                    if (connector.direction == Connector.Direction.INPUT) {
                        val freshnessState = when {
                            info?.lastMessageAt == null -> "idle"
                            info.lastMessageAt!!.isAfter(now.minus(15, ChronoUnit.MINUTES)) -> "fresh"
                            else -> "stale"
                        }
                        increment(connectorMessageFreshness, "protocol=websocket,state=$freshnessState")
                    }
                }

                else -> Unit
            }

            val pollingState = when {
                connector.connectorType !in connectorPollingService.POLLABLE_TYPES -> "unsupported"
                connector.direction != Connector.Direction.INPUT -> "not_input"
                !connector.isActive -> "inactive"
                !connector.pollingEnabled -> "disabled"
                connector.id?.let { connectorPollingService.getPollingInfo(it).scheduled } == true -> "scheduled"
                else -> "unscheduled"
            }
            increment(connectorPolling, "state=$pollingState")
        }

        return MonitoringSnapshot(
            collectedAt = now,
            pipelineStates = fillStatuses(pipelineStateRows),
            recentPipelineStates = fillStatuses(recentPipelineStateRows),
            staleRunningCount = staleRunningCount,
            connectorHealth = connectorHealth,
            connectorProtocols = connectorProtocols,
            connectorMessageFreshness = connectorMessageFreshness,
            connectorPolling = connectorPolling,
        )
    }

    private fun summarizeHealth(connector: Connector): String {
        if (!connector.isActive) return "inactive"
        val checks = connectorStatusService.getStatus(connector).checks
        if (checks.any { it.status == "ERROR" }) return "down"
        if (checks.any { it.status == "WARN" || it.status == "UNKNOWN" }) return "degraded"
        return "healthy"
    }

    private fun fillStatuses(rows: List<Array<Any>>): Map<String, Long> {
        val counts = PipelineRunStatus.entries.associate { it.name.lowercase() to 0L }.toMutableMap()
        rows.forEach { row ->
            val status = (row[0] as PipelineRunStatus).name.lowercase()
            counts[status] = row[1] as Long
        }
        return counts
    }

    private fun increment(target: MutableMap<String, Long>, key: String) {
        target[key] = (target[key] ?: 0L) + 1
    }
}
