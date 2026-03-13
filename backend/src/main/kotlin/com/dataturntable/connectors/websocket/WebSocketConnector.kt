package com.dataturntable.connectors.websocket

import com.dataturntable.connectors.InputConnector
import com.dataturntable.connectors.OutputConnector
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Multi
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.WebSocket
import io.vertx.mutiny.ext.web.client.WebClient
import io.vertx.mutiny.ext.web.client.WebClientOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class WebSocketConnector {
    
    private val LOG = Logger.getLogger(WebSocketConnector::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var vertx: Vertx
    
    fun createWebSocketInput(config: Map<String, Any>): WebSocketInputConnector {
        return WebSocketInputConnector(config, vertx)
    }
    
    fun createWebSocketOutput(config: Map<String, Any>): WebSocketOutputConnector {
        return WebSocketOutputConnector(config, vertx)
    }
}

class WebSocketInputConnector(
    private val config: Map<String, Any>,
    private val vertx: Vertx
) : InputConnector {
    
    private val LOG = Logger.getLogger(WebSocketInputConnector::class.java)
    private val objectMapper = ObjectMapper()
    private var client: WebClient? = null
    private var ws: WebSocket? = null
    private val messages = mutableListOf<Map<String, Any>>()
    private val lock = Object()
    
    override fun connect(config: Map<String, Any>) {
        val url = config["url"] as? String ?: throw IllegalArgumentException("url is required")
        val ssl = config["ssl"] as? Boolean ?: url.startsWith("wss://")
        
        val host = extractHost(url)
        val port = extractPort(url, ssl)
        val path = extractPath(url)
        
        val options = WebClientOptions()
            .setDefaultHost(host)
            .setDefaultPort(port)
            .setSsl(ssl)
        
        client = WebClient.create(vertx, options)
        
        client?.webSocket(path)?.handler { frame ->
            if (frame.isText) {
                handleMessage(frame.textData())
            }
        }?.open()?.invoke { ws ->
            this.ws = ws
            LOG.infof("WebSocket input connected to %s", url)
        }?.onFailure()?.invoke { e ->
            LOG.errorf(e, "Failed to connect to WebSocket: %s", url)
        }
    }
    
    private fun handleMessage(data: String) {
        try {
            val parsed = try {
                objectMapper.readValue(data, Map::class.java) as? Map<String, Any>
            } catch (e: Exception) {
                mapOf("raw" to data)
            }
            
            synchronized(lock) {
                messages.add(parsed ?: emptyMap())
            }
        } catch (e: Exception) {
            LOG.error("Error processing WebSocket message", e)
        }
    }
    
    override fun read(): List<Map<String, Any>> {
        synchronized(lock) {
            val result = messages.toList()
            messages.clear()
            return result
        }
    }
    
    override fun readOne(): Map<String, Any>? {
        synchronized(lock) {
            return if (messages.isNotEmpty()) messages.removeAt(0) else null
        }
    }
    
    override fun disconnect() {
        ws?.close()
        client = null
        ws = null
    }
    
    override fun isConnected(): Boolean = ws != null
    
    fun observe(): Multi<Map<String, Any>> {
        return Multi.createFrom().emitter { emitter ->
            client?.webSocket(extractPath(config["url"] as? String ?: ""))?.handler { frame ->
                if (frame.isText) {
                    try {
                        val parsed = objectMapper.readValue(frame.textData(), Map::class.java) as Map<String, Any>
                        emitter.emit(parsed)
                    } catch (e: Exception) {
                        LOG.error("Error in WebSocket observe", e)
                    }
                }
            }?.open()?.onFailure()?.invoke { e ->
                emitter.fail(e)
            }
        }
    }
    
    private fun extractHost(url: String): String {
        return try {
            java.net.URI.create(url).host ?: "localhost"
        } catch (e: Exception) {
            "localhost"
        }
    }
    
    private fun extractPort(url: String, ssl: Boolean): Int {
        return try {
            val uri = java.net.URI.create(url)
            if (uri.port > 0) uri.port else if (ssl) 443 else 80
        } catch (e: Exception) {
            if (ssl) 443 else 80
        }
    }
    
    private fun extractPath(url: String): String {
        return try {
            val uri = java.net.URI.create(url)
            (uri.path ?: "") + if (uri.query != null) "?${uri.query}" else ""
        } catch (e: Exception) {
            url
        }
    }
}

class WebSocketOutputConnector(
    private val config: Map<String, Any>,
    private val vertx: Vertx
) : OutputConnector {
    
    private val LOG = Logger.getLogger(WebSocketOutputConnector::class.java)
    private val objectMapper = ObjectMapper()
    private var client: WebClient? = null
    private var ws: WebSocket? = null
    
    override fun connect(config: Map<String, Any>) {
        val url = config["url"] as? String ?: throw IllegalArgumentException("url is required")
        val ssl = config["ssl"] as? Boolean ?: url.startsWith("wss://")
        
        val host = extractHost(url)
        val port = extractPort(url, ssl)
        val path = extractPath(url)
        
        val options = WebClientOptions()
            .setDefaultHost(host)
            .setDefaultPort(port)
            .setSsl(ssl)
        
        client = WebClient.create(vertx, options)
        
        client?.webSocket(path)?.open()?.invoke { ws ->
            this.ws = ws
            LOG.infof("WebSocket output connected to %s", url)
        }?.onFailure()?.invoke { e ->
            LOG.errorf(e, "Failed to connect to WebSocket: %s", url)
        }
    }
    
    override fun write(data: List<Map<String, Any>>) {
        data.forEach { item ->
            writeOne(item)
        }
    }
    
    override fun writeOne(data: Map<String, Any>) {
        try {
            val json = objectMapper.writeValueAsString(data)
            ws?.write(Buffer.buffer(json))?.onFailure()?.invoke { e ->
                LOG.errorf(e, "Failed to write WebSocket message")
            }
        } catch (e: Exception) {
            LOG.error("Error serializing WebSocket message", e)
        }
    }
    
    override fun disconnect() {
        ws?.close()
        client = null
        ws = null
    }
    
    override fun isConnected(): Boolean = ws != null
    
    private fun extractHost(url: String): String {
        return try {
            java.net.URI.create(url).host ?: "localhost"
        } catch (e: Exception) {
            "localhost"
        }
    }
    
    private fun extractPort(url: String, ssl: Boolean): Int {
        return try {
            val uri = java.net.URI.create(url)
            if (uri.port > 0) uri.port else if (ssl) 443 else 80
        } catch (e: Exception) {
            if (ssl) 443 else 80
        }
    }
    
    private fun extractPath(url: String): String {
        return try {
            val uri = java.net.URI.create(url)
            (uri.path ?: "") + if (uri.query != null) "?${uri.query}" else ""
        } catch (e: Exception) {
            url
        }
    }
}
