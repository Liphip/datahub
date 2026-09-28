package com.dataturntable.connectors.mqtt

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorLeaseService
import com.dataturntable.connectors.ConnectorMetrics
import com.dataturntable.security.SensitiveDataRedactor
import io.quarkus.runtime.StartupEvent
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
/**
 * Defines the class `MqttConnectionManager`, the contract implemented by this source unit.
 */
class MqttConnectionManager {

    private val LOG = Logger.getLogger(MqttConnectionManager::class.java)

    @Inject
    lateinit var messageRouter: MqttMessageRouter

    @Inject
    lateinit var connectorMetrics: ConnectorMetrics

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var connectorLeaseService: ConnectorLeaseService

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    @ConfigProperty(name = "dataturntable.connectors.lease.reconcile-interval-seconds", defaultValue = "10")
    var leaseReconcileIntervalSeconds: Long = 20

    /** connectorId → active Paho client */
    private val clients = ConcurrentHashMap<UUID, MqttClient>()

    /** connectorId → topics currently subscribed */
    private val subscribedTopicsMap = ConcurrentHashMap<UUID, List<String>>()

    /** connectorId → last broker error message */
    private val clientErrors = ConcurrentHashMap<UUID, String>()

    /** connectorId → time of last received message */
    private val lastMessageTimes = ConcurrentHashMap<UUID, Instant>()

    /** Fencing generation captured by each live inbound client. */
    private val leaseTokens = ConcurrentHashMap<UUID, Long>()

    /** In-memory routing table, rebuilt by messageRouter.buildRoutingTable() */
    @Volatile
    private var routingTable: Map<UUID, List<MqttMessageRouter.RouteEntry>> = emptyMap()

    private val reconcileExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var reconcileTask: ScheduledFuture<*>? = null

    // ── Status info ───────────────────────────────────────────────────────────

    /**
     * Defines the class `MqttClientInfo`, the contract implemented by this source unit.
     */
    data class MqttClientInfo(
        val connected: Boolean,
        val subscribedTopics: List<String>,
        val lastMessageAt: Instant?,
        val lastError: String?,
    )

    /**
     * Retrieves or enumerates the requested state.
     */
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

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    fun onStart(@Observes event: StartupEvent) {
        if (!runtimeRoleConfig.ownsIngestRuntime()) {
            LOG.infof("MqttConnectionManager startup skipped for runtime role %s", runtimeRoleConfig.role)
            return
        }
        LOG.info("MqttConnectionManager starting — loading MQTT connectors")
        refreshAll()
        reconcileTask?.cancel(false)
        reconcileTask = reconcileExecutor.scheduleAtFixedRate(
            { refreshAll() },
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
        LOG.info("MqttConnectionManager shutting down — disconnecting all MQTT clients")
        clients.forEach { (id, client) ->
            try { client.disconnect(1000) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            LOG.infof("Disconnected MQTT client for connector %s", id)
        }
        clients.clear()
        reconcileTask?.cancel(false)
        reconcileExecutor.shutdownNow()
        connectorLeaseService.releaseAllOwned()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Rebuild the full routing table and reconnect/resubscribe all MQTT INPUT connectors.
     * Called on startup and when pipelines are activated/deactivated.
     */
    fun refreshAll() {
        if (!runtimeRoleConfig.ownsIngestRuntime()) return // connections live on ingest replicas only
        try {
            val newTable = messageRouter.buildRoutingTable()
            routingTable = newTable

            val connectorIds = messageRouter.findAllActiveMqttInputConnectors()
            val ownedConnectorIds = mutableSetOf<UUID>()
            for (connectorId in connectorIds) {
                try {
                    val claim = connectorLeaseService.acquireOrRenew(connectorId)
                    if (claim == null) {
                        disconnectConnector(connectorId, releaseLease = false)
                        continue
                    }
                    ownedConnectorIds += connectorId
                    leaseTokens[connectorId] = claim.fencingToken
                    connectAndSubscribe(connectorId, claim.fencingToken)
                } catch (e: Exception) {
                    LOG.errorf("Failed to connect MQTT connector %s: %s", connectorId, e.message)
                }
            }

            // Disconnect connectors that are no longer active or no longer leased here
            clients.keys.filter { it !in ownedConnectorIds }.forEach { id ->
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
        if (!runtimeRoleConfig.ownsIngestRuntime()) return // connections live on ingest replicas only
        try {
            routingTable = messageRouter.buildRoutingTable()
            val (connector, _) = messageRouter.resolveConnectorConfig(connectorId) ?: run {
                disconnectConnector(connectorId)
                return
            }
            if (connector.connectorType != Connector.ConnectorType.MQTT) return
            val claim = if (connector.direction == Connector.Direction.INPUT && connector.isActive) connectorLeaseService.acquireOrRenew(connectorId) else null
            if (claim != null) {
                leaseTokens[connectorId] = claim.fencingToken
                connectAndSubscribe(connectorId, claim.fencingToken)
            } else {
                disconnectConnector(connectorId)
            }
        } catch (e: Exception) {
            LOG.errorf("Failed to refresh MQTT connector %s: %s", connectorId, e.message)
        }
    }

    /**
     * Disconnect and remove a connector's MQTT client.
     *
     * [ConnectorResource]'s create/update/delete callbacks call this directly (a fast path, so a
     * connector stops the moment it's deleted/deactivated rather than waiting for the next
     * [refreshAll] reconcile tick) from wherever the HTTP request happened to land — which, in an
     * HA deployment with API and ingest roles split across replica pools, need not be the replica
     * actually holding this connector's client or lease. Only release the lease when this replica
     * owns ingest runtime: a control-api-only replica releasing a lease it never held would let
     * another ingest replica acquire it (with a fresh fencing token) while the true owner's client
     * is still live and unaware its token is now stale — two replicas consuming the same connector
     * concurrently. The owning replica's own [refreshAll] already disconnects and releases
     * correctly once it next reconciles (within `leaseReconcileIntervalSeconds`), so nothing here
     * is lost by not doing it from a replica that was never the owner.
     */
    fun disconnectConnector(connectorId: UUID, releaseLease: Boolean = true) {
        clients.remove(connectorId)?.let { client ->
            try { client.disconnect(1000) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            LOG.infof("Disconnected MQTT client for connector %s", connectorId)
        }
        subscribedTopicsMap.remove(connectorId)
        leaseTokens.remove(connectorId)
        if (releaseLease && runtimeRoleConfig.ownsIngestRuntime()) {
            connectorLeaseService.release(connectorId)
        }
    }

    /**
     * Publish a JSON payload to an MQTT OUTPUT connector's configured topic.
     * Creates a short-lived Paho client if no persistent client exists for this broker.
     */
    fun publish(connectorId: UUID, decryptedConfig: Map<String, Any?>, topicOverride: String?, payloadJson: String) {
        val startedAt = System.nanoTime()
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
            connectorMetrics.recordOperation("MQTT", "OUTPUT", "publish", "success", startedAt)
            LOG.infof("MQTT OUTPUT: published %d bytes to topic '%s' on %s", payloadJson.length, topic, redactor.text(brokerUrl))
            LOG.infof(
                "event=connector_publish component=mqtt connector_id=%s connector_type=mqtt direction=output outcome=success topic=%s bytes=%d",
                connectorId,
                topic,
                payloadJson.length
            )
        } catch (e: Exception) {
            connectorMetrics.recordOperation("MQTT", "OUTPUT", "publish", "failure", startedAt)
            LOG.errorf("MQTT OUTPUT publish failed for connector %s on topic '%s': %s", connectorId, topic, redactor.text(e.message))
            throw RuntimeException("MQTT publish failed: ${e.message}", e)
        } finally {
            try { client.disconnect(500) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    private fun connectAndSubscribe(connectorId: UUID, fencingToken: Long) {
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
        client.setCallback(buildCallback(connectorId, client, topics, fencingToken))

        val opts = buildConnectOptions(config)
        try {
            client.connect(opts)
            subscribeTopics(connectorId, client, topics)
            clients[connectorId] = client
            subscribedTopicsMap[connectorId] = topics
            clientErrors.remove(connectorId)
            connectorMetrics.connectionCounter("mqtt", "success").increment()
            if (topics.isEmpty()) {
                LOG.infof("MQTT connector %s connected to %s (no subscriptions yet — add mqttTopic to a pipeline IMPORT node)", connectorId, redactor.text(brokerUrl))
            } else {
                LOG.infof("MQTT connector %s connected to %s, subscribed to: %s", connectorId, redactor.text(brokerUrl), topics)
            }
            LOG.infof(
                "event=connector_connection component=mqtt connector_id=%s connector_type=mqtt direction=input outcome=success broker=%s subscriptions=%d",
                connectorId,
                redactor.text(brokerUrl),
                topics.size
            )
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
            clientErrors[connectorId] = e.message ?: "Connection failed"
            subscribedTopicsMap.remove(connectorId)
            connectorMetrics.connectionCounter("mqtt", "failure").increment()
            LOG.errorf("MQTT connector %s failed to connect to %s: %s", connectorId, redactor.text(brokerUrl), redactor.text(e.message))
            throw e
        }
    }

    /**
     * Implements `subscribeTopics` for this source unit.
     */
    private fun subscribeTopics(connectorId: UUID, client: MqttClient, topics: List<String>) {
        if (topics.isEmpty()) return
        client.subscribe(topics.toTypedArray(), IntArray(topics.size) { 1 })
        LOG.debugf("MQTT connector %s subscribed to topics: %s", connectorId, topics)
    }

    /**
     * Implements `buildCallback` for this source unit.
     */
    private fun buildCallback(
        connectorId: UUID,
        client: MqttClient,
        topics: List<String>,
        fencingToken: Long
    ): MqttCallbackExtended {
        return object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (!connectorLeaseService.owns(connectorId, fencingToken)) {
                    clientErrors[connectorId] = "Lease ownership lost"
                    disconnectConnector(connectorId, releaseLease = false)
                    return
                }
                if (reconnect) {
                    LOG.infof("MQTT connector %s reconnected to %s — re-subscribing to %s", connectorId, redactor.text(serverURI), topics)
                    try {
                        subscribeTopics(connectorId, client, topics)
                        subscribedTopicsMap[connectorId] = topics
                        clientErrors.remove(connectorId)
                    } catch (e: Exception) {
                        clientErrors[connectorId] = "Re-subscribe failed: ${redactor.text(e.message)}"
                        LOG.errorf("MQTT connector %s re-subscribe failed: %s", connectorId, redactor.text(e.message))
                    }
                }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                if (!connectorLeaseService.owns(connectorId, fencingToken)) {
                    clientErrors[connectorId] = "Lease ownership lost"
                    disconnectConnector(connectorId, releaseLease = false)
                    return
                }
                val payload = message.payload?.toString(Charsets.UTF_8) ?: return
                lastMessageTimes[connectorId] = Instant.now()
                connectorMetrics.messageCounter("mqtt", "input", "received").increment()
                LOG.debugf("MQTT connector %s received message on topic '%s' (%d bytes)", connectorId, topic, payload.length)
                try {
                    messageRouter.routeMessage(connectorId, topic, payload, routingTable)
                } catch (e: Exception) {
                    // Throwing makes Paho drop the connection without sending PUBACK; the broker
                    // keeps the message in the persistent session and redelivers it after the
                    // automatic reconnect (at-least-once).
                    connectorMetrics.messageCounter("mqtt", "input", "failed").increment()
                    LOG.errorf("MQTT connector %s message routing failed, message will be redelivered: %s", connectorId, redactor.text(e.message))
                    throw e
                }
            }

            override fun connectionLost(cause: Throwable?) {
                clientErrors[connectorId] = redactor.text(cause?.message) ?: "Connection lost"
                subscribedTopicsMap.remove(connectorId)
                connectorMetrics.connectionCounter("mqtt", "lost").increment()
                LOG.warnf("MQTT connector %s connection lost: %s", connectorId, redactor.text(cause?.message))
                // automaticReconnect=true handles reconnection; connectComplete() re-subscribes
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        }
    }

    /**
     * Implements `buildConnectOptions` for this source unit.
     */
    private fun buildConnectOptions(config: Map<String, Any?>): MqttConnectOptions {
        return MqttConnectOptions().apply {
            // Persistent session with a stable client id: QoS 1 messages published while the
            // connector is disconnected (restart, failover to another ingest replica) are
            // queued by the broker and delivered when the new lease holder connects.
            isCleanSession = false
            isAutomaticReconnect = true
            maxReconnectDelay = 30_000
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
    /**
     * Implements `extractPort` for this source unit.
     */
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
            true to "Connected to ${redactor.text(brokerUrl)}"
        } catch (e: Exception) {
            false to (redactor.text(e.message) ?: "Connection failed")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
