package com.dataturntable.connectors.mqtt

import com.dataturntable.connectors.Connector
import io.quarkus.runtime.StartupEvent
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MqttConnectionManager manages Eclipse Paho MQTT clients for all active MQTT connectors.
 *
 * Design:
 * - One Paho MqttClient per MQTT INPUT connector (different brokers/credentials per connector)
 * - On startup: loads all active MQTT INPUT connectors, builds routing table from active pipelines,
 *   connects each client and subscribes to the topics its pipelines need
 * - On message: routes to matching pipelines via MqttMessageRouter (transactional CDI bean)
 * - Reconnect: MqttCallbackExtended.connectComplete() re-subscribes on reconnect
 * - Publish: for MQTT OUTPUT connectors, creates temporary clients or reuses existing ones
 *   (same broker URL as INPUT connectors when the broker is shared)
 */
@ApplicationScoped
class MqttConnectionManager {

    private val LOG = Logger.getLogger(MqttConnectionManager::class.java)

    @Inject
    lateinit var messageRouter: MqttMessageRouter

    /** connectorId → active Paho client */
    private val clients = ConcurrentHashMap<UUID, MqttClient>()

    /** connectorId → topics currently subscribed */
    private val subscribedTopicsMap = ConcurrentHashMap<UUID, List<String>>()

    /** connectorId → last broker error message */
    private val clientErrors = ConcurrentHashMap<UUID, String>()

    /** connectorId → time of last received message */
    private val lastMessageTimes = ConcurrentHashMap<UUID, Instant>()

    /** In-memory routing table, rebuilt by messageRouter.buildRoutingTable() */
    @Volatile
    private var routingTable: Map<UUID, List<MqttMessageRouter.RouteEntry>> = emptyMap()

    // ── Status info ───────────────────────────────────────────────────────────

    data class MqttClientInfo(
        val connected: Boolean,
        val subscribedTopics: List<String>,
        val lastMessageAt: Instant?,
        val lastError: String?,
    )

    fun getClientInfo(connectorId: UUID): MqttClientInfo {
        val client = clients[connectorId]
        return MqttClientInfo(
            connected = client?.isConnected ?: false,
            subscribedTopics = subscribedTopicsMap[connectorId] ?: emptyList(),
            lastMessageAt = lastMessageTimes[connectorId],
            lastError = clientErrors[connectorId],
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onStart(@Observes event: StartupEvent) {
        LOG.info("MqttConnectionManager starting — loading MQTT connectors")
        refreshAll()
    }

    fun onStop(@Observes event: ShutdownEvent) {
        LOG.info("MqttConnectionManager shutting down — disconnecting all MQTT clients")
        clients.forEach { (id, client) ->
            try { client.disconnect(1000) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            LOG.infof("Disconnected MQTT client for connector %s", id)
        }
        clients.clear()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuild the full routing table and reconnect/resubscribe all MQTT INPUT connectors.
     * Called on startup and when pipelines are activated/deactivated.
     */
    fun refreshAll() {
        try {
            val newTable = messageRouter.buildRoutingTable()
            routingTable = newTable

            val connectorIds = messageRouter.findAllActiveMqttInputConnectors()
            for (connectorId in connectorIds) {
                try {
                    connectAndSubscribe(connectorId)
                } catch (e: Exception) {
                    LOG.errorf("Failed to connect MQTT connector %s: %s", connectorId, e.message)
                }
            }

            // Disconnect connectors that are no longer active
            val activeIds = connectorIds.toSet()
            clients.keys.filter { it !in activeIds }.forEach { id ->
                disconnectConnector(id)
            }
        } catch (e: Exception) {
            LOG.errorf("MQTT refreshAll failed: %s", e.message)
        }
    }

    /**
     * Refresh a specific connector (after create/update/delete).
     * Rebuilds the routing table and reconnects/resubscribes this connector.
     */
    fun refreshConnector(connectorId: UUID) {
        try {
            routingTable = messageRouter.buildRoutingTable()
            val (connector, _) = messageRouter.resolveConnectorConfig(connectorId) ?: run {
                disconnectConnector(connectorId)
                return
            }
            if (connector.connectorType != Connector.ConnectorType.MQTT) return
            if (connector.direction == Connector.Direction.INPUT && connector.isActive) {
                connectAndSubscribe(connectorId)
            } else {
                disconnectConnector(connectorId)
            }
        } catch (e: Exception) {
            LOG.errorf("Failed to refresh MQTT connector %s: %s", connectorId, e.message)
        }
    }

    /**
     * Disconnect and remove a connector's MQTT client.
     */
    fun disconnectConnector(connectorId: UUID) {
        clients.remove(connectorId)?.let { client ->
            try { client.disconnect(1000) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            LOG.infof("Disconnected MQTT client for connector %s", connectorId)
        }
        subscribedTopicsMap.remove(connectorId)
    }

    /**
     * Publish a JSON payload to an MQTT OUTPUT connector's configured topic.
     * Creates a short-lived Paho client if no persistent client exists for this broker.
     */
    fun publish(connectorId: UUID, decryptedConfig: Map<String, Any?>, topicOverride: String?, payloadJson: String) {
        val brokerUrl = normalizeBrokerUrl(decryptedConfig["broker"]?.toString())
            ?: run { LOG.warnf("MQTT OUTPUT connector %s has no broker URL", connectorId); return }
        val topic = topicOverride?.takeIf { it.isNotBlank() }
            ?: decryptedConfig["mqttTopic"]?.toString()?.trim()
            ?: decryptedConfig["topic"]?.toString()?.trim()
            ?: run { LOG.warnf("MQTT OUTPUT connector %s has no topic configured", connectorId); return }

        val clientId = "dt-out-${connectorId.toString().take(8)}-${System.currentTimeMillis() % 10000}"
        val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
        try {
            val opts = buildConnectOptions(decryptedConfig)
            opts.isAutomaticReconnect = false
            client.connect(opts)
            val msg = MqttMessage(payloadJson.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
            client.publish(topic, msg)
            LOG.infof("MQTT OUTPUT: published %d bytes to topic '%s' on %s", payloadJson.length, topic, brokerUrl)
        } catch (e: Exception) {
            LOG.errorf("MQTT OUTPUT publish failed for connector %s on topic '%s': %s", connectorId, topic, e.message)
            throw RuntimeException("MQTT publish failed: ${e.message}", e)
        } finally {
            try { client.disconnect(500) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun connectAndSubscribe(connectorId: UUID) {
        val (connector, config) = messageRouter.resolveConnectorConfig(connectorId) ?: return
        val brokerUrl = normalizeBrokerUrl(config["broker"]?.toString())
            ?: run { LOG.warnf("MQTT connector %s has no broker URL — skipping", connectorId); return }

        val entries = routingTable[connectorId] ?: emptyList()
        val topics = entries.map { it.topicPattern }.distinct()
        if (topics.isEmpty()) {
            LOG.infof("MQTT connector %s has no pipeline topic subscriptions yet — connecting to broker without subscriptions", connectorId)
        }

        // Disconnect existing client for this connector before reconnecting
        clients.remove(connectorId)?.let { old ->
            try { old.disconnect(1000) } catch (_: Exception) {}
            try { old.close() } catch (_: Exception) {}
        }

        val clientId = (config["clientId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: "dt-${connectorId.toString().take(8)}")
            .take(23) // Paho clientId max length is 23

        val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
        client.setCallback(buildCallback(connectorId, client, topics))

        val opts = buildConnectOptions(config)
        try {
            client.connect(opts)
            subscribeTopics(connectorId, client, topics)
            clients[connectorId] = client
            subscribedTopicsMap[connectorId] = topics
            clientErrors.remove(connectorId)
            if (topics.isEmpty()) {
                LOG.infof("MQTT connector %s connected to %s (no subscriptions yet — add mqttTopic to a pipeline IMPORT node)", connectorId, brokerUrl)
            } else {
                LOG.infof("MQTT connector %s connected to %s, subscribed to: %s", connectorId, brokerUrl, topics)
            }
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
            clientErrors[connectorId] = e.message ?: "Connection failed"
            subscribedTopicsMap.remove(connectorId)
            LOG.errorf("MQTT connector %s failed to connect to %s: %s", connectorId, brokerUrl, e.message)
            throw e
        }
    }

    private fun subscribeTopics(connectorId: UUID, client: MqttClient, topics: List<String>) {
        if (topics.isEmpty()) return
        client.subscribe(topics.toTypedArray(), IntArray(topics.size) { 1 })
        LOG.debugf("MQTT connector %s subscribed to topics: %s", connectorId, topics)
    }

    private fun buildCallback(
        connectorId: UUID,
        client: MqttClient,
        topics: List<String>
    ): MqttCallbackExtended {
        return object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    LOG.infof("MQTT connector %s reconnected to %s — re-subscribing to %s", connectorId, serverURI, topics)
                    try {
                        subscribeTopics(connectorId, client, topics)
                        subscribedTopicsMap[connectorId] = topics
                        clientErrors.remove(connectorId)
                    } catch (e: Exception) {
                        clientErrors[connectorId] = "Re-subscribe failed: ${e.message}"
                        LOG.errorf("MQTT connector %s re-subscribe failed: %s", connectorId, e.message)
                    }
                }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = message.payload?.toString(Charsets.UTF_8) ?: return
                lastMessageTimes[connectorId] = Instant.now()
                LOG.debugf("MQTT connector %s received message on topic '%s' (%d bytes)", connectorId, topic, payload.length)
                try {
                    messageRouter.routeMessage(connectorId, topic, payload, routingTable)
                } catch (e: Exception) {
                    LOG.errorf("MQTT connector %s message routing failed: %s", connectorId, e.message)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                clientErrors[connectorId] = cause?.message ?: "Connection lost"
                subscribedTopicsMap.remove(connectorId)
                LOG.warnf("MQTT connector %s connection lost: %s", connectorId, cause?.message)
                // automaticReconnect=true handles reconnection; connectComplete() re-subscribes
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        }
    }

    private fun buildConnectOptions(config: Map<String, Any?>): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 10
            keepAliveInterval = 30
            val username = config["username"]?.toString()?.trim()
            val password = config["password"]?.toString()?.trim()
            if (!username.isNullOrBlank()) {
                this.userName = username
                if (!password.isNullOrBlank()) {
                    this.password = password.toCharArray()
                }
            }
        }
    }

    /**
     * Normalize a user-supplied broker URL to the Paho-required tcp:// or ssl:// scheme.
     *
     * Rules:
     *  - mqtts:// → ssl://   (explicit TLS scheme)
     *  - mqtt://host:8883    → ssl://  (port 8883 is IANA-assigned MQTT-over-TLS)
     *  - mqtt://host:*       → tcp://  (all other ports are plain)
     *  - bare host:8883      → ssl://host:8883
     *  - bare host:*         → tcp://host:*
     *  - tcp:// / ssl://     → pass-through
     */
    private fun normalizeBrokerUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("ssl://")   -> trimmed
            trimmed.startsWith("tcp://")   -> trimmed
            trimmed.startsWith("mqtts://") -> trimmed.replaceFirst("mqtts://", "ssl://")
            trimmed.startsWith("mqtt://")  -> {
                val port = extractPort(trimmed.removePrefix("mqtt://"))
                if (port == 8883) trimmed.replaceFirst("mqtt://", "ssl://")
                else trimmed.replaceFirst("mqtt://", "tcp://")
            }
            else -> {
                // Bare host[:port] — no scheme
                val port = extractPort(trimmed)
                if (port == 8883) "ssl://$trimmed" else "tcp://$trimmed"
            }
        }
    }

    /** Extract the numeric port from a host:port string (ignores trailing path / query). */
    private fun extractPort(hostAndRest: String): Int? =
        hostAndRest.substringAfterLast(":", "").substringBefore("/").trim().toIntOrNull()

    /**
     * Attempt a brief (5-second) connection to verify the broker is reachable and the
     * credentials are accepted. Does not subscribe to any topics.
     * Returns (true, success-message) or (false, error-message).
     */
    fun testBrokerConnection(decryptedConfig: Map<String, Any?>): Pair<Boolean, String> {
        val brokerUrl = normalizeBrokerUrl(decryptedConfig["broker"]?.toString())
            ?: return false to "No broker URL configured"
        val testClientId = "dt-test-${System.currentTimeMillis() % 100000}"
        val client = MqttClient(brokerUrl, testClientId, MemoryPersistence())
        return try {
            val opts = buildConnectOptions(decryptedConfig).apply {
                isAutomaticReconnect = false
                connectionTimeout = 5
            }
            client.connect(opts)
            try { client.disconnect(1000) } catch (_: Exception) {}
            true to "Connected to $brokerUrl"
        } catch (e: Exception) {
            false to (e.message ?: "Connection failed")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
