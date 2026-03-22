package com.dataturntable.connectors.websocket

import com.dataturntable.connectors.Connector
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
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocketConnectionManager manages persistent WebSocket INPUT connections and handles
 * one-shot sends for WebSocket OUTPUT connectors.
 *
 * INPUT: Connects to external WebSocket servers and routes incoming messages to
 *        matching pipelines via Kafka (same pattern as MqttConnectionManager).
 *
 * OUTPUT: For each send, creates a temporary WebSocket connection, sends the payload,
 *         and closes. Reuses an existing INPUT connection if one exists for the same URL.
 */
@ApplicationScoped
class WebSocketConnectionManager {

    private val LOG = Logger.getLogger(WebSocketConnectionManager::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Inject
    lateinit var wsMessageRouter: WebSocketMessageRouter

    /** connectorId → active WebSocket connection */
    private val connections = ConcurrentHashMap<UUID, WebSocket>()

    /** connectorId → last connection error */
    private val connectionErrors = ConcurrentHashMap<UUID, String>()

    /** connectorId → time of last received message */
    private val lastMessageTimes = ConcurrentHashMap<UUID, Instant>()

    // ── Status info ───────────────────────────────────────────────────────────

    data class WebSocketClientInfo(
        val connected: Boolean,
        val lastMessageAt: Instant?,
        val lastError: String?,
    )

    fun getClientInfo(connectorId: UUID): WebSocketClientInfo {
        val ws = connections[connectorId]
        return WebSocketClientInfo(
            connected = ws != null && !ws.isInputClosed,
            lastMessageAt = lastMessageTimes[connectorId],
            lastError = connectionErrors[connectorId],
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onStart(@Observes event: StartupEvent) {
        LOG.info("WebSocketConnectionManager starting — connecting WebSocket INPUT connectors")
        refreshAll()
    }

    fun onStop(@Observes event: ShutdownEvent) {
        LOG.info("WebSocketConnectionManager shutting down")
        connections.forEach { (id, ws) ->
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").get(2, TimeUnit.SECONDS) } catch (_: Exception) {}
            LOG.infof("Closed WebSocket connection for connector %s", id)
        }
        connections.clear()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun refreshAll() {
        try {
            val connectorIds = wsMessageRouter.findAllActiveWebSocketInputConnectors()
            for (connectorId in connectorIds) {
                try { connectConnector(connectorId) } catch (e: Exception) {
                    LOG.errorf("Failed to connect WebSocket connector %s: %s", connectorId, e.message)
                }
            }
            // Disconnect connectors no longer active
            val activeSet = connectorIds.toSet()
            connections.keys.filter { it !in activeSet }.forEach { disconnectConnector(it) }
        } catch (e: Exception) {
            LOG.errorf("WebSocket refreshAll failed: %s", e.message)
        }
    }

    fun refreshConnector(connectorId: UUID) {
        try {
            val (connector, _) = wsMessageRouter.resolveConnectorConfig(connectorId) ?: run {
                disconnectConnector(connectorId)
                return
            }
            if (connector.direction == Connector.Direction.INPUT && connector.isActive) {
                connectConnector(connectorId)
            } else {
                disconnectConnector(connectorId)
            }
        } catch (e: Exception) {
            LOG.errorf("Failed to refresh WebSocket connector %s: %s", connectorId, e.message)
        }
    }

    fun disconnectConnector(connectorId: UUID) {
        connections.remove(connectorId)?.let { ws ->
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "disconnected").get(2, TimeUnit.SECONDS) } catch (_: Exception) {}
            LOG.infof("Disconnected WebSocket connector %s", connectorId)
        }
    }

    /**
     * Send a JSON payload to a WebSocket OUTPUT connector.
     * Creates a temporary connection if no persistent connection exists.
     */
    fun send(connectorId: UUID, decryptedConfig: Map<String, Any?>, payloadJson: String) {
        val url = buildWsUrl(decryptedConfig)
            ?: run { LOG.warnf("WebSocket OUTPUT connector %s has no URL", connectorId); return }

        // Try reusing an existing INPUT connection to the same URL
        val existing = connections.values.firstOrNull()
        if (existing != null && !existing.isInputClosed) {
            try {
                existing.sendText(payloadJson, true).get(10, TimeUnit.SECONDS)
                LOG.infof("WebSocket OUTPUT: sent %d bytes via existing connection for connector %s", payloadJson.length, connectorId)
                return
            } catch (_: Exception) { /* fall through to fresh connection */ }
        }

        // Create a one-shot connection
        val done = CompletableFuture<Void>()
        val listener = object : WebSocket.Listener {
            override fun onOpen(ws: WebSocket) {
                ws.sendText(payloadJson, true).whenComplete { _, err ->
                    if (err != null) done.completeExceptionally(err)
                    else {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                        done.complete(null)
                    }
                }
            }
            override fun onError(ws: WebSocket, error: Throwable) {
                done.completeExceptionally(error)
            }
        }
        try {
            httpClient.newWebSocketBuilder()
                .apply {
                    val token = decryptedConfig["authToken"]?.toString()
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }
                .buildAsync(URI.create(url), listener)
                .get(15, TimeUnit.SECONDS)
            done.get(10, TimeUnit.SECONDS)
            LOG.infof("WebSocket OUTPUT: sent %d bytes to %s for connector %s", payloadJson.length, url, connectorId)
        } catch (e: Exception) {
            LOG.errorf("WebSocket OUTPUT send failed for connector %s to %s: %s", connectorId, url, e.message)
            throw RuntimeException("WebSocket send failed: ${e.message}", e)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun connectConnector(connectorId: UUID) {
        val (connector, config) = wsMessageRouter.resolveConnectorConfig(connectorId) ?: return
        val url = buildWsUrl(config)
            ?: run { LOG.warnf("WebSocket connector %s has no URL — skipping", connectorId); return }

        disconnectConnector(connectorId)

        val messageBuffer = StringBuilder()
        val listener = object : WebSocket.Listener {
            override fun onOpen(ws: WebSocket) {
                LOG.infof("WebSocket connector %s connected to %s", connectorId, url)
                connectionErrors.remove(connectorId)
                ws.request(1)
            }

            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                messageBuffer.append(data)
                if (last) {
                    val msg = messageBuffer.toString()
                    messageBuffer.clear()
                    lastMessageTimes[connectorId] = Instant.now()
                    try {
                        wsMessageRouter.routeMessage(connectorId, msg)
                    } catch (e: Exception) {
                        LOG.errorf("WebSocket connector %s routing failed: %s", connectorId, e.message)
                    }
                }
                ws.request(1)
                return null
            }

            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                ws.request(1)
                return null
            }

            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                LOG.infof("WebSocket connector %s closed (%d: %s) — will attempt reconnect", connectorId, statusCode, reason)
                connections.remove(connectorId)
                connectionErrors[connectorId] = "Closed (code $statusCode: $reason)"
                // Schedule reconnect after 5s
                Thread.sleep(5000)
                try { connectConnector(connectorId) } catch (_: Exception) {}
                return null
            }

            override fun onError(ws: WebSocket, error: Throwable) {
                LOG.warnf("WebSocket connector %s error: %s", connectorId, error.message)
                connections.remove(connectorId)
                connectionErrors[connectorId] = error.message ?: "WebSocket error"
            }
        }

        try {
            val ws = httpClient.newWebSocketBuilder()
                .apply {
                    val token = config["authToken"]?.toString()
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }
                .buildAsync(URI.create(url), listener)
                .get(15, TimeUnit.SECONDS)
            connections[connectorId] = ws
        } catch (e: Exception) {
            connectionErrors[connectorId] = e.message ?: "Failed to connect"
            LOG.errorf("WebSocket connector %s failed to connect to %s: %s", connectorId, url, e.message)
            throw e
        }
    }

    private fun buildWsUrl(config: Map<String, Any?>): String? {
        val url = config["url"]?.toString()?.trim() ?: return null
        if (url.isBlank()) return null
        val path = config["path"]?.toString()?.trim()?.let { if (it.startsWith("/")) it else "/$it" } ?: ""
        return if (path.isNotBlank() && !url.contains(path)) "$url$path" else url
    }
}

/**
 * Transactional CDI bean for WebSocket message routing (same pattern as MqttMessageRouter).
 */
@ApplicationScoped
class WebSocketMessageRouter {

    private val LOG = Logger.getLogger(WebSocketMessageRouter::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var kafkaService: KafkaService

    @PersistenceContext
    lateinit var em: EntityManager

    @Transactional
    fun routeMessage(connectorId: UUID, payloadStr: String) {
        val pipelines = findPipelinesUsingConnector(connectorId)
        if (pipelines.isEmpty()) return

        val payload: Any? = try {
            objectMapper.readValue(payloadStr, Any::class.java)
        } catch (_: Exception) { payloadStr }

        for (pipelineId in pipelines) {
            val pipeline = em.find(Pipeline::class.java, pipelineId) ?: continue
            if (!pipeline.isActive) continue

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.triggerType = TriggerType.DATA
                this.status = PipelineRunStatus.PENDING
                this.startedAt = java.time.Instant.now()
            }
            em.persist(run)
            em.flush()

            kafkaService.triggerPipelineWithData(
                pipelineId = pipeline.id!!.toString(),
                runId = run.id!!.toString(),
                triggerType = TriggerType.DATA,
                data = mapOf("_connectorId" to connectorId.toString(), "payload" to payload)
            )
            LOG.infof("WebSocket connector %s triggered pipeline %s (run %s)", connectorId, pipeline.id, run.id)
        }
    }

    @Transactional
    fun resolveConnectorConfig(connectorId: UUID): Pair<Connector, Map<String, Any?>>? {
        val connector = em.find(Connector::class.java, connectorId) ?: return null
        val raw = try { EncryptionUtils.decrypt(connector.config) } catch (_: Exception) { connector.config }
        val config = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) { emptyMap() }
        return connector to config
    }

    @Transactional
    fun findAllActiveWebSocketInputConnectors(): List<UUID> {
        return em.createQuery(
            "SELECT c.id FROM Connector c WHERE c.connectorType = :type AND c.direction = :dir AND c.isActive = true",
            UUID::class.java
        )
            .setParameter("type", Connector.ConnectorType.WEBSOCKET)
            .setParameter("dir", Connector.Direction.INPUT)
            .resultList
    }

    private fun findPipelinesUsingConnector(connectorId: UUID): List<UUID> {
        val activePipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.isActive = true",
            Pipeline::class.java
        ).resultList

        return activePipelines.mapNotNull { pipeline ->
            val graph = try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(pipeline.graphDefinition ?: return@mapNotNull null, Map::class.java) as? Map<String, Any>
            } catch (_: Exception) { null } ?: return@mapNotNull null
            val nodes = graph["nodes"] as? List<*> ?: return@mapNotNull null
            val found = nodes.any { node ->
                val m = node as? Map<*, *> ?: return@any false
                m["type"]?.toString()?.uppercase() == "IMPORT" && m["connectorId"]?.toString() == connectorId.toString()
            }
            if (found) pipeline.id else null
        }
    }
}
