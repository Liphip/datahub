package com.dataturntable.connectors.websocket

import com.dataturntable.connectors.InputConnector
import com.dataturntable.connectors.OutputConnector
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class WebSocketConnector {
    
    private val LOG = Logger.getLogger(WebSocketConnector::class.java)
    
    fun createWebSocketInput(config: Map<String, Any>): WebSocketInputConnector {
        return WebSocketInputConnector(config)
    }
    
    fun createWebSocketOutput(config: Map<String, Any>): WebSocketOutputConnector {
        return WebSocketOutputConnector(config)
    }
}

class WebSocketInputConnector(
    private val config: Map<String, Any>
) : InputConnector {
    
    private val LOG = Logger.getLogger(WebSocketInputConnector::class.java)
    private var connected = false
    private val messages = mutableListOf<Map<String, Any>>()
    
    override fun connect(config: Map<String, Any>) {
        val url = config["url"] as? String ?: throw IllegalArgumentException("url is required")
        LOG.infof("WebSocket input connecting to %s (stub)", url)
        connected = true
    }
    
    override fun read(): List<Map<String, Any>> {
        val result = messages.toList()
        messages.clear()
        return result
    }
    
    override fun readOne(): Map<String, Any>? {
        return if (messages.isNotEmpty()) messages.removeAt(0) else null
    }
    
    override fun disconnect() {
        connected = false
    }
    
    override fun isConnected(): Boolean = connected
}

class WebSocketOutputConnector(
    private val config: Map<String, Any>
) : OutputConnector {
    
    private val LOG = Logger.getLogger(WebSocketOutputConnector::class.java)
    private var connected = false
    
    override fun connect(config: Map<String, Any>) {
        val url = config["url"] as? String ?: throw IllegalArgumentException("url is required")
        LOG.infof("WebSocket output connecting to %s (stub)", url)
        connected = true
    }
    
    override fun write(data: List<Map<String, Any>>) {
        if (!connected) return
        LOG.debugf("WebSocket stub wrote %d messages", data.size)
    }
    
    override fun writeOne(data: Map<String, Any>) {
        if (!connected) return
        LOG.debugf("WebSocket stub wrote 1 message")
    }
    
    override fun disconnect() {
        connected = false
    }
    
    override fun isConnected(): Boolean = connected
}
