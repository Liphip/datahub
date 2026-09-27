package com.dataturntable.connectors

import com.dataturntable.connectors.Connector.ConnectorType
import com.dataturntable.connectors.Connector.Direction
import com.dataturntable.connectors.mqtt.MqttConnectionManager
import com.dataturntable.connectors.websocket.WebSocketConnectionManager
import com.dataturntable.security.EncryptionUtils
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import com.dataturntable.security.SensitiveDataRedactor
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import com.dataturntable.connectors.inbox.FileSandbox
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * ConnectorRuntimeService — platform-level fetch and deliver.
 *
 * Responsibilities:
 *   - IMPORT node: before the module runs, the platform calls [fetchFromConnector] to retrieve
 *     raw data from the external system. The module receives this data as INPUT_DATA and
 *     only transforms/parses it. No network calls inside module code.
 *
 *   - EXPORT node: after the module shapes the outbound payload, the platform calls
 *     [deliverViaConnector] to send it to the external system. No network calls inside module code.
 *
 * Connector behaviour per type:
 *   REST  INPUT  → HTTP GET  to baseUrl+path; returns response body as Map
 *   REST  OUTPUT → HTTP POST to baseUrl+path with shaped payload as JSON body
 *   MQTT  INPUT  → data already arrived via MqttConnectionManager push; fetch is a no-op
 *   MQTT  OUTPUT → publish JSON payload to configured topic (via MqttPublishService)
 *   WEBSOCKET INPUT  → push-based; fetch is a no-op
 *   WEBSOCKET OUTPUT → send JSON payload over the configured WebSocket
 *   UDP   INPUT  → push-based; fetch is a no-op
 *   FILE  INPUT  → read file at configured path, return content
 *   FILE  OUTPUT → write payload to configured path
 *   CUSTOM → delegated to registered plugin; no-op stub here
 */
@ApplicationScoped
/**
 * Defines the class `ConnectorRuntimeService`, the contract implemented by this source unit.
 */
class ConnectorRuntimeService {

    @ConfigProperty(name = "dataturntable.connectors.file.base-dir", defaultValue = "/data/files")
    lateinit var fileBaseDir: String

    private val LOG = Logger.getLogger(ConnectorRuntimeService::class.java)
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Inject
    lateinit var mqttConnectionManager: MqttConnectionManager

    @Inject
    lateinit var webSocketConnectionManager: WebSocketConnectionManager

    @Inject
    lateinit var connectorMetrics: ConnectorMetrics

    @PersistenceContext
    lateinit var em: EntityManager
    @Inject
    lateinit var secretReferenceResolver: ConnectorSecretReferenceResolver

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Fetch raw data from an INPUT connector on behalf of the platform.
     * Returns null if the connector is push-based (data was already injected as inputData).
     */
    fun fetchFromConnector(connector: Connector, decryptedConfig: Map<String, Any?>): Any? {
        val startedAt = System.nanoTime()
        if (connector.direction != Direction.INPUT) {
            LOG.warnf("fetchFromConnector called on OUTPUT connector %s — skipping", connector.id)
            connectorMetrics.recordOperation(
                connector.connectorType?.name,
                connector.direction?.name,
                "fetch",
                "skipped",
                startedAt
            )
            return null
        }
        return try {
            val result = when (connector.connectorType) {
                ConnectorType.REST     -> fetchRestInput(decryptedConfig)
                // Inboxes are read by InboxPoller, which acknowledges items at the source.
                ConnectorType.FILE,
                ConnectorType.FTP,
                ConnectorType.SFTP,
                ConnectorType.EMAIL    -> null
                // Push-based connectors: data was already delivered by the ingest trigger
                ConnectorType.MQTT,
                ConnectorType.WEBSOCKET,
                ConnectorType.UDP      -> null
                ConnectorType.CUSTOM   -> null
                null                   -> null
            }
            connectorMetrics.recordOperation(
                connector.connectorType?.name,
                connector.direction?.name,
                "fetch",
                if (result == null) "skipped" else "success",
                startedAt
            )
            result
        } catch (e: Exception) {
            connectorMetrics.recordOperation(
                connector.connectorType?.name,
                connector.direction?.name,
                "fetch",
                "failure",
                startedAt
            )
            throw e
        }
    }

    /**
     * Deliver shaped data via an OUTPUT connector on behalf of the platform.
     * Called after the EXPORT module has run and produced its result.
     */
    /**
     * Delivers [data] through an OUTPUT connector. [deliveryKey] identifies this delivery
     * (run and node): it is sent as `Idempotency-Key` on REST so that a receiver can drop the
     * repeat of a delivery that is re-executed after a failover.
     */
    fun deliverViaConnector(connector: Connector, decryptedConfig: Map<String, Any?>, data: Any?, deliveryKey: String? = null) {
        val startedAt = System.nanoTime()
        if (connector.direction != Direction.OUTPUT) {
            LOG.warnf("deliverViaConnector called on INPUT connector %s — skipping", connector.id)
            connectorMetrics.recordOperation(
                connector.connectorType?.name,
                connector.direction?.name,
                "deliver",
                "skipped",
                startedAt
            )
            return
        }
        try {
            when (connector.connectorType) {
                ConnectorType.REST      -> deliverRestOutput(decryptedConfig, data, deliveryKey)
                ConnectorType.FILE      -> deliverFileOutput(decryptedConfig, data)
                ConnectorType.MQTT      -> deliverMqttOutput(decryptedConfig, data, connector)
                ConnectorType.WEBSOCKET -> deliverWebSocketOutput(decryptedConfig, data, connector)
                ConnectorType.UDP       -> deliverUdpOutput(decryptedConfig, data)
                ConnectorType.FTP,
                ConnectorType.SFTP,
                ConnectorType.EMAIL     -> throw IllegalArgumentException("${connector.connectorType} connectors are input only")
                ConnectorType.CUSTOM    -> LOG.infof("CUSTOM connector %s — no-op deliver", connector.id)
                null                    -> Unit
            }
            connectorMetrics.recordOperation(
                connector.connectorType?.name,
                connector.direction?.name,
                "deliver",
                if (connector.connectorType == ConnectorType.CUSTOM || connector.connectorType == null) "skipped" else "success",
                startedAt
            )
        } catch (e: Exception) {
            connectorMetrics.recordOperation(
                connector.connectorType?.name,
                connector.direction?.name,
                "deliver",
                "failure",
                startedAt
            )
            throw e
        }
    }

    /**
     * Decrypt and parse connector config. Callers should use this before passing to fetch/deliver.
     */
    fun decryptConfig(connector: Connector): Map<String, Any?> {
        val raw = try {
            EncryptionUtils.decrypt(connector.config)
        } catch (_: Exception) {
            connector.config
        }
        val config = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            emptyMap()
        }
        return secretReferenceResolver.resolve(connector, config)
    }

    /**
     * Decrypted config plus platform-side secrets needed to talk to the external system
     * (MQTT broker credentials are resolved from the tenant's credential store).
     * Never pass the result to module containers.
     */
    fun resolveRuntimeConfig(connector: Connector): Map<String, Any?> {
        val config = decryptConfig(connector).toMutableMap()
        if (connector.connectorType == ConnectorType.MQTT) {
            val credentialId = config["brokerCredentialId"]?.toString()
                ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            val credential = credentialId?.let { em.find(BrokerCredential::class.java, it) }
            if (credential != null && credential.tenant?.id == connector.tenant?.id && credential.isActive) {
                config["username"] = credential.username
                credential.passwordEncrypted
                    ?.let { runCatching { EncryptionUtils.decrypt(it) }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { config["password"] = it }
                config["brokerCredentialName"] = credential.name
            }
        }
        return config
    }

    // ── REST ─────────────────────────────────────────────────────────────────

    /**
     * Implements `fetchRestInput` for this source unit.
     */
    private fun fetchRestInput(config: Map<String, Any?>): Any? {
        val baseUrl = config["baseUrl"]?.toString()?.trimEnd('/') ?: run {
            LOG.warn("REST INPUT connector missing baseUrl — skipping fetch")
            return null
        }
        val path = config["path"]?.toString()?.let { if (it.startsWith("/")) it else "/$it" } ?: ""
        val url = "$baseUrl$path"

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()

        applyRestHeaders(requestBuilder, config)

        LOG.infof("REST INPUT fetch: GET %s", redactor.text(url))
        return try {
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            LOG.infof("REST INPUT response: HTTP %d, body length %d", response.statusCode(), response.body().length)
            parseResponseBody(response.body())
        } catch (e: Exception) {
            LOG.errorf("REST INPUT fetch failed: %s", redactor.text(e.message))
            throw RuntimeException("REST connector fetch failed: ${e.message}", e)
        }
    }

    /**
     * Implements `deliverRestOutput` for this source unit.
     */
    private fun deliverRestOutput(config: Map<String, Any?>, data: Any?, deliveryKey: String?) {
        val baseUrl = config["baseUrl"]?.toString()?.trimEnd('/')
            ?: throw IllegalArgumentException("REST OUTPUT connector has no baseUrl")
        val path = config["path"]?.toString()?.let { if (it.startsWith("/")) it else "/$it" } ?: ""
        val method = config["method"]?.toString()?.uppercase() ?: "POST"
        val url = "$baseUrl$path"
        val body = if (data != null) objectMapper.writeValueAsString(data) else "{}"

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
        deliveryKey?.let { requestBuilder.header("Idempotency-Key", it) }
        applyRestHeaders(requestBuilder, config)
        when (method) {
            "PUT"   -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body))
            "PATCH" -> requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            else    -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body))
        }
        val request = requestBuilder.build()

        // Transient failures (network, 429, 5xx) are retried briefly; anything left fails the
        // run, which keeps its input for Retry. Other 4xx answers are not retried.
        var lastError: String? = null
        for (attempt in 1..REST_ATTEMPTS) {
            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                val status = response.statusCode()
                if (status in 200..299) {
                    LOG.debugf("REST OUTPUT %s %s -> %d", method, url, status)
                    return
                }
                lastError = "HTTP $status: ${response.body().take(200)}"
                if (status != 429 && status < 500) break
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            if (attempt < REST_ATTEMPTS) Thread.sleep(1000L * attempt * attempt)
        }
        throw RuntimeException("REST connector delivery to ${redactor.text(url)} failed: ${redactor.text(lastError)}")
    }

    /**
     * Implements `applyRestHeaders` for this source unit.
     */
    private fun applyRestHeaders(builder: HttpRequest.Builder, config: Map<String, Any?>) {
        val authToken = config["authToken"]?.toString()
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $authToken")
        }
        val apiKey = config["apiKey"]?.toString()
        val apiKeyHeader = config["apiKeyHeader"]?.toString() ?: "X-Api-Key"
        if (!apiKey.isNullOrBlank()) {
            builder.header(apiKeyHeader, apiKey)
        }
        @Suppress("UNCHECKED_CAST")
        val extraHeaders = config["headers"] as? Map<String, String>
        extraHeaders?.forEach { (k, v) -> builder.header(k, v) }
    }

    // ── FILE ─────────────────────────────────────────────────────────────────

    /**
     * Implements `deliverFileOutput` for this source unit.
     */
    private fun deliverFileOutput(config: Map<String, Any?>, data: Any?) {
        val path = config["path"]?.toString() ?: return
        val body = if (data != null) objectMapper.writeValueAsString(data) else "{}"
        try {
            val file = FileSandbox(fileBaseDir).resolve(path)
            java.nio.file.Files.createDirectories(file.parent)
            java.nio.file.Files.writeString(file, body)
            LOG.infof("FILE OUTPUT wrote %d bytes to %s", body.length, path)
        } catch (e: Exception) {
            LOG.errorf("FILE OUTPUT write failed at %s: %s", path, e.message)
            throw RuntimeException("FILE connector write failed: ${e.message}", e)
        }
    }

    // ── MQTT ─────────────────────────────────────────────────────────────────

    /**
     * Implements `deliverMqttOutput` for this source unit.
     */
    private fun deliverMqttOutput(config: Map<String, Any?>, data: Any?, connector: Connector) {
        val payloadJson = if (data != null) objectMapper.writeValueAsString(data) else "{}"
        val topicOverride = config["mqttTopic"]?.toString()?.trim()
            ?: config["topic"]?.toString()?.trim()
        mqttConnectionManager.publish(
            connectorId = connector.id!!,
            decryptedConfig = config,
            topicOverride = topicOverride,
            payloadJson = payloadJson
        )
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    /**
     * Implements `deliverWebSocketOutput` for this source unit.
     */
    private fun deliverWebSocketOutput(config: Map<String, Any?>, data: Any?, connector: Connector) {
        val payloadJson = if (data != null) objectMapper.writeValueAsString(data) else "{}"
        webSocketConnectionManager.send(
            connectorId = connector.id!!,
            decryptedConfig = config,
            payloadJson = payloadJson
        )
    }

    // ── UDP ───────────────────────────────────────────────────────────────────

    /**
     * Implements `deliverUdpOutput` for this source unit.
     */
    private fun deliverUdpOutput(config: Map<String, Any?>, data: Any?) {
        val host = config["host"]?.toString() ?: return
        val port = config["port"]?.toString()?.toIntOrNull() ?: return
        val body = if (data != null) objectMapper.writeValueAsBytes(data) else ByteArray(0)
        try {
            java.net.DatagramSocket().use { socket ->
                val addr = java.net.InetAddress.getByName(host)
                socket.send(java.net.DatagramPacket(body, body.size, addr, port))
                LOG.infof("UDP OUTPUT: sent %d bytes to %s:%d", body.size, host, port)
            }
        } catch (e: Exception) {
            LOG.errorf("UDP OUTPUT deliver failed: %s", e.message)
            throw RuntimeException("UDP connector deliver failed: ${e.message}", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseResponseBody(body: String): Any? {
        if (body.isBlank()) return null
        return try {
            objectMapper.readValue(body, Any::class.java)
        } catch (_: Exception) {
            body
        }
    }

    private companion object {
        const val REST_ATTEMPTS = 3
    }
}
