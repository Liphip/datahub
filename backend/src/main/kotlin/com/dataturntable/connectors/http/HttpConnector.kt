package com.dataturntable.connectors.http

import com.dataturntable.connectors.InputConnector
import com.dataturntable.connectors.OutputConnector
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
/**
 * Defines the class `HttpConnector`, the contract implemented by this source unit.
 */
class HttpConnector {
    
    private val LOG = Logger.getLogger(HttpConnector::class.java)
    
    /**
     * Creates or persists the requested state.
     */
    fun createRestInput(config: Map<String, Any>): HttpInputConnector {
        return HttpInputConnector(config)
    }
    
    /**
     * Creates or persists the requested state.
     */
    fun createRestOutput(config: Map<String, Any>): HttpOutputConnector {
        return HttpOutputConnector(config)
    }
}

/**
 * Defines the class `HttpInputConnector`, the contract implemented by this source unit.
 */
class HttpInputConnector(
    private val config: Map<String, Any>
) : InputConnector {
    
    private val LOG = Logger.getLogger(HttpInputConnector::class.java)
    private var connected = false
    
    override fun connect(config: Map<String, Any>) {
        val baseUrl = config["baseUrl"] as? String ?: throw IllegalArgumentException("baseUrl is required")
        LOG.infof("HTTP input connecting to %s (stub)", baseUrl)
        connected = true
    }
    
    override fun read(): List<Map<String, Any>> {
        return emptyList()
    }
    
    override fun readOne(): Map<String, Any>? {
        return null
    }
    
    override fun disconnect() {
        connected = false
    }
    
    override fun isConnected(): Boolean = connected
}

/**
 * Defines the class `HttpOutputConnector`, the contract implemented by this source unit.
 */
class HttpOutputConnector(
    private val config: Map<String, Any>
) : OutputConnector {
    
    private val LOG = Logger.getLogger(HttpOutputConnector::class.java)
    private var connected = false
    
    override fun connect(config: Map<String, Any>) {
        val baseUrl = config["baseUrl"] as? String ?: throw IllegalArgumentException("baseUrl is required")
        LOG.infof("HTTP output connecting to %s (stub)", baseUrl)
        connected = true
    }
    
    override fun write(data: List<Map<String, Any>>) {
        if (!connected) return
        LOG.debugf("HTTP stub wrote %d messages", data.size)
    }
    
    override fun writeOne(data: Map<String, Any>) {
        if (!connected) return
        LOG.debugf("HTTP stub wrote 1 message")
    }
    
    override fun disconnect() {
        connected = false
    }
    
    override fun isConnected(): Boolean = connected
}
