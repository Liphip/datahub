package com.dataturntable.connectors

import com.dataturntable.connectors.mqtt.MqttConnectionManager
import com.dataturntable.connectors.polling.ConnectorPollingService
import com.dataturntable.connectors.websocket.WebSocketConnectionManager
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

/**
 * Aggregates runtime health/status information for a connector from the relevant
 * connection manager(s) into a list of named checks.
 *
 * All state is read from in-memory maps maintained by MqttConnectionManager,
 * WebSocketConnectionManager, and ConnectorPollingService — no DB access needed.
 */
@ApplicationScoped
/**
 * Defines the class `ConnectorStatusService`, the contract implemented by this source unit.
 */
class ConnectorStatusService {

    @Inject
    lateinit var mqttConnectionManager: MqttConnectionManager

    @Inject
    lateinit var webSocketConnectionManager: WebSocketConnectionManager

    @Inject
    lateinit var pollingService: ConnectorPollingService

    /**
     * Retrieves or enumerates the requested state.
     */
    fun getStatus(connector: Connector): ConnectorStatusDto {
        val checks = mutableListOf<StatusCheckDto>()
        val id = connector.id ?: return ConnectorStatusDto("unknown", connector.connectorType?.name ?: "UNKNOWN", connector.direction?.name ?: "UNKNOWN", listOf(StatusCheckDto("Configuration", "UNKNOWN", "Connector ID not set", null)))

        when (connector.connectorType) {
            Connector.ConnectorType.MQTT      -> buildMqttChecks(connector, id, checks)
            Connector.ConnectorType.WEBSOCKET -> buildWebSocketChecks(connector, id, checks)
            Connector.ConnectorType.REST      -> buildRestChecks(connector, id, checks)
            Connector.ConnectorType.FILE      -> buildFileChecks(connector, id, checks)
            Connector.ConnectorType.UDP       -> buildUdpChecks(connector, checks)
            Connector.ConnectorType.CUSTOM    -> checks += ok("Custom handler", "Custom connector configured")
            null -> checks += unknown("Configuration", "Connector type not set")
        }

        return ConnectorStatusDto(
            connectorId   = id.toString(),
            connectorType = connector.connectorType?.name ?: "UNKNOWN",
            direction     = connector.direction?.name ?: "UNKNOWN",
            checks        = checks,
        )
    }

    // ── Per-type builders ─────────────────────────────────────────────────────

    /**
     * Implements `buildMqttChecks` for this source unit.
     */
    private fun buildMqttChecks(connector: Connector, id: UUID, checks: MutableList<StatusCheckDto>) {
        if (!connector.isActive) {
            checks += warn("Connector", "Inactive — click Connect to activate")
            return
        }
        when (connector.direction) {
            Connector.Direction.INPUT -> {
                val info = mqttConnectionManager.getClientInfo(id)
                checks += if (info.connected) ok("Broker connection", "Connected to broker")
                           else error("Broker connection", info.lastError ?: "Not connected — check broker URL and credentials")

                if (info.connected) {
                    checks += if (info.subscribedTopics.isNotEmpty())
                        ok("Topic subscriptions", "${info.subscribedTopics.size} topic(s): ${info.subscribedTopics.joinToString(", ")}")
                    else
                        warn("Topic subscriptions", "No topics subscribed — assign this connector to an active pipeline IMPORT node with an MQTT Topic")
                }
                if (info.lastMessageAt != null) {
                    checks += ok("Last message", formatInstant(info.lastMessageAt))
                }
            }
            Connector.Direction.OUTPUT -> {
                checks += ok("Broker config", "OUTPUT — short-lived connection created per publish")
            }
            null -> checks += unknown("Direction", "Direction not configured")
        }
    }

    /**
     * Implements `buildWebSocketChecks` for this source unit.
     */
    private fun buildWebSocketChecks(connector: Connector, id: UUID, checks: MutableList<StatusCheckDto>) {
        if (!connector.isActive) {
            checks += warn("Connector", "Inactive — click Connect to activate")
            return
        }
        when (connector.direction) {
            Connector.Direction.INPUT -> {
                val info = webSocketConnectionManager.getClientInfo(id)
                checks += if (info.connected) ok("WebSocket connection", "Connected")
                           else error("WebSocket connection", info.lastError ?: "Not connected — check URL and credentials")
                if (info.lastMessageAt != null) {
                    checks += ok("Last message", formatInstant(info.lastMessageAt))
                }
            }
            Connector.Direction.OUTPUT -> {
                checks += ok("WebSocket config", "OUTPUT — short-lived connection created per send")
            }
            null -> checks += unknown("Direction", "Direction not configured")
        }
    }

    /**
     * Implements `buildRestChecks` for this source unit.
     */
    private fun buildRestChecks(connector: Connector, id: UUID, checks: MutableList<StatusCheckDto>) {
        if (!connector.isActive) {
            checks += warn("Connector", "Inactive — click Connect to activate")
            return
        }
        if (connector.direction == Connector.Direction.INPUT && connector.pollingEnabled) {
            val info = pollingService.getPollingInfo(id)
            checks += if (info.scheduled) ok("Polling", "Scheduled every ${info.intervalSeconds}s")
                       else warn("Polling", "Not scheduled — connector may have just been configured")
            if (info.lastRunAt != null) {
                checks += if (info.lastRunSuccess == true)
                    ok("Last poll", formatInstant(info.lastRunAt))
                else
                    error("Last poll", "Failed at ${formatInstant(info.lastRunAt)}: ${info.lastRunError ?: "unknown error"}")
            }
        } else {
            val dirMsg = if (connector.direction == Connector.Direction.OUTPUT)
                "OUTPUT — sends on pipeline run" else "INPUT — polling disabled"
            checks += ok("REST endpoint", dirMsg)
        }
    }

    /**
     * Implements `buildFileChecks` for this source unit.
     */
    private fun buildFileChecks(connector: Connector, id: UUID, checks: MutableList<StatusCheckDto>) {
        if (!connector.isActive) {
            checks += warn("Connector", "Inactive — click Connect to activate")
            return
        }
        if (connector.direction == Connector.Direction.INPUT && connector.pollingEnabled) {
            val info = pollingService.getPollingInfo(id)
            checks += if (info.scheduled) ok("Polling", "Scheduled every ${info.intervalSeconds}s")
                       else warn("Polling", "Not scheduled — connector may have just been configured")
            if (info.lastRunAt != null) {
                checks += if (info.lastRunSuccess == true)
                    ok("Last read", formatInstant(info.lastRunAt))
                else
                    error("Last read", "Failed at ${formatInstant(info.lastRunAt)}: ${info.lastRunError ?: "unknown error"}")
            }
        } else {
            val dirMsg = if (connector.direction == Connector.Direction.OUTPUT)
                "OUTPUT — writes on pipeline run" else "INPUT — polling disabled"
            checks += ok("File config", dirMsg)
        }
    }

    /**
     * Implements `buildUdpChecks` for this source unit.
     */
    private fun buildUdpChecks(connector: Connector, checks: MutableList<StatusCheckDto>) {
        // UDP uses no persistent connection manager yet
        checks += if (connector.isActive) ok("UDP endpoint", "Configured")
                  else warn("UDP endpoint", "Inactive — click Connect to activate")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Implements `ok` for this source unit.
     */
    private fun ok(name: String, message: String)      = StatusCheckDto(name, "OK",      message, null)
    /**
     * Implements `warn` for this source unit.
     */
    private fun warn(name: String, message: String)    = StatusCheckDto(name, "WARN",    message, null)
    /**
     * Implements `error` for this source unit.
     */
    private fun error(name: String, message: String)   = StatusCheckDto(name, "ERROR",   message, null)
    /**
     * Implements `unknown` for this source unit.
     */
    private fun unknown(name: String, message: String) = StatusCheckDto(name, "UNKNOWN", message, null)

    /**
     * Implements `formatInstant` for this source unit.
     */
    private fun formatInstant(instant: java.time.Instant): String =
        java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

/**
 * Defines the class `ConnectorStatusDto`, the contract implemented by this source unit.
 */
data class ConnectorStatusDto(
    val connectorId:   String,
    val connectorType: String,
    val direction:     String,
    val checks:        List<StatusCheckDto>,
)

/**
 * Defines the class `StatusCheckDto`, the contract implemented by this source unit.
 */
data class StatusCheckDto(
    val name:    String,
    val status:  String,   // "OK" | "WARN" | "ERROR" | "UNKNOWN"
    val message: String?,
    val detail:  String?,
)
