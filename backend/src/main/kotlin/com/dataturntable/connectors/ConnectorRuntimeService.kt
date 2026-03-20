package com.dataturntable.connectors

import com.dataturntable.connectors.Connector.ConnectorType
import com.dataturntable.connectors.Connector.Direction
import com.dataturntable.security.EncryptionUtils
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
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
 *   MQTT  INPUT  → data already arrived via MqttIngestService push; fetch is a no-op
 *   MQTT  OUTPUT → publish JSON payload to configured topic (via MqttPublishService)
 *   WEBSOCKET INPUT  → push-based; fetch is a no-op
 *   WEBSOCKET OUTPUT → send JSON payload over the configured WebSocket
 *   UDP   INPUT  → push-based; fetch is a no-op
 *   FILE  INPUT  → read file at configured path, return content
 *   FILE  OUTPUT → write payload to configured path
 *   CUSTOM → delegated to registered plugin; no-op stub here
 */
@ApplicationScoped
class ConnectorRuntimeService {

    private val LOG = Logger.getLogger(ConnectorRuntimeService::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Fetch raw data from an INPUT connector on behalf of the platform.
     * Returns null if the connector is push-based (data was already injected as inputData).
     */
    fun fetchFromConnector(connector: Connector, decryptedConfig: Map<String, Any?>): Any? {
        if (connector.direction != Direction.INPUT) {
            LOG.warnf("fetchFromConnector called on OUTPUT connector %s — skipping", connector.id)
            return null
        }
        return when (connector.connectorType) {
            ConnectorType.REST     -> fetchRestInput(decryptedConfig)
            ConnectorType.FILE     -> fetchFileInput(decryptedConfig)
            // Push-based connectors: data was already delivered by the ingest trigger
            ConnectorType.MQTT,
            ConnectorType.WEBSOCKET,
            ConnectorType.UDP      -> null
            ConnectorType.CUSTOM   -> null
            null                   -> null
        }
    }

    /**
     * Deliver shaped data via an OUTPUT connector on behalf of the platform.
     * Called after the EXPORT module has run and produced its result.
     */
    fun deliverViaConnector(connector: Connector, decryptedConfig: Map<String, Any?>, data: Any?) {
        if (connector.direction != Direction.OUTPUT) {
            LOG.warnf("deliverViaConnector called on INPUT connector %s — skipping", connector.id)
            return
        }
        when (connector.connectorType) {
            ConnectorType.REST      -> deliverRestOutput(decryptedConfig, data)
            ConnectorType.FILE      -> deliverFileOutput(decryptedConfig, data)
            ConnectorType.MQTT      -> deliverMqttOutput(decryptedConfig, data)
            ConnectorType.WEBSOCKET -> deliverWebSocketOutput(decryptedConfig, data)
            ConnectorType.UDP       -> deliverUdpOutput(decryptedConfig, data)
            ConnectorType.CUSTOM    -> LOG.infof("CUSTOM connector %s — no-op deliver", connector.id)
            null                    -> Unit
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
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── REST ─────────────────────────────────────────────────────────────────

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

        LOG.infof("REST INPUT fetch: GET %s", url)
        return try {
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            LOG.infof("REST INPUT response: HTTP %d, body length %d", response.statusCode(), response.body().length)
            parseResponseBody(response.body())
        } catch (e: Exception) {
            LOG.errorf("REST INPUT fetch failed: %s", e.message)
            throw RuntimeException("REST connector fetch failed: ${e.message}", e)
        }
    }

    private fun deliverRestOutput(config: Map<String, Any?>, data: Any?) {
        val baseUrl = config["baseUrl"]?.toString()?.trimEnd('/') ?: run {
            LOG.warn("REST OUTPUT connector missing baseUrl — skipping deliver")
            return
        }
        val path = config["path"]?.toString()?.let { if (it.startsWith("/")) it else "/$it" } ?: ""
        val method = config["method"]?.toString()?.uppercase() ?: "POST"
        val url = "$baseUrl$path"
        val body = if (data != null) objectMapper.writeValueAsString(data) else "{}"

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")

        applyRestHeaders(requestBuilder, config)

        when (method) {
            "PUT"   -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body))
            "PATCH" -> requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            else    -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body))
        }

        LOG.infof("REST OUTPUT deliver: %s %s", method, url)
        try {
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            LOG.infof("REST OUTPUT response: HTTP %d", response.statusCode())
            if (response.statusCode() >= 400) {
                LOG.warnf("REST OUTPUT returned error status %d: %s", response.statusCode(), response.body().take(200))
            }
        } catch (e: Exception) {
            LOG.errorf("REST OUTPUT deliver failed: %s", e.message)
            throw RuntimeException("REST connector deliver failed: ${e.message}", e)
        }
    }

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

    private fun fetchFileInput(config: Map<String, Any?>): Any? {
        val path = config["path"]?.toString() ?: return null
        return try {
            val content = java.io.File(path).readText()
            parseResponseBody(content)
        } catch (e: Exception) {
            LOG.errorf("FILE INPUT read failed at %s: %s", path, e.message)
            throw RuntimeException("FILE connector read failed: ${e.message}", e)
        }
    }

    private fun deliverFileOutput(config: Map<String, Any?>, data: Any?) {
        val path = config["path"]?.toString() ?: return
        val body = if (data != null) objectMapper.writeValueAsString(data) else "{}"
        try {
            java.io.File(path).writeText(body)
            LOG.infof("FILE OUTPUT wrote %d bytes to %s", body.length, path)
        } catch (e: Exception) {
            LOG.errorf("FILE OUTPUT write failed at %s: %s", path, e.message)
            throw RuntimeException("FILE connector write failed: ${e.message}", e)
        }
    }

    // ── MQTT ─────────────────────────────────────────────────────────────────

    private fun deliverMqttOutput(config: Map<String, Any?>, data: Any?) {
        // TODO: wire to MqttPublishService once available
        val broker = config["broker"]?.toString() ?: "unknown"
        val topic  = config["topic"]?.toString() ?: "unknown"
        LOG.infof("MQTT OUTPUT (stub): publish to %s on %s — implement MqttPublishService", topic, broker)
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun deliverWebSocketOutput(config: Map<String, Any?>, data: Any?) {
        // TODO: implement WebSocket send via WebSocketConnector
        val url = config["url"]?.toString() ?: "unknown"
        LOG.infof("WEBSOCKET OUTPUT (stub): send to %s — implement WebSocketConnector.send()", url)
    }

    // ── UDP ───────────────────────────────────────────────────────────────────

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

    private fun parseResponseBody(body: String): Any? {
        if (body.isBlank()) return null
        return try {
            objectMapper.readValue(body, Any::class.java)
        } catch (_: Exception) {
            body
        }
    }
}
