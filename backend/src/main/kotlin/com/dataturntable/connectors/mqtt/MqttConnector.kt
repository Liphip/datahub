package com.dataturntable.connectors.mqtt

import com.dataturntable.connectors.InputConnector
import com.dataturntable.connectors.OutputConnector
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class MqttConnector {
    
    private val LOG = Logger.getLogger(MqttConnector::class.java)
    
    fun connectAsInput(config: Map<String, Any>): MqttInputConnector {
        return MqttInputConnector(config)
    }
    
    fun connectAsOutput(config: Map<String, Any>): MqttOutputConnector {
        return MqttOutputConnector(config)
    }
}

class MqttInputConnector(
    private val config: Map<String, Any>
) : InputConnector {
    
    private val LOG = Logger.getLogger(MqttInputConnector::class.java)
    private var connected = false
    private val messages = mutableListOf<Map<String, Any>>()
    
    override fun connect(config: Map<String, Any>) {
        val host = config["host"] as? String ?: "localhost"
        val port = (config["port"] as? Number)?.toInt() ?: 1883
        val topic = config["topic"] as? String ?: "#"
        
        LOG.infof("MQTT input connecting to %s:%d, topic: %s (stub)", host, port, topic)
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

class MqttOutputConnector(
    private val config: Map<String, Any>
) : OutputConnector {
    
    private val LOG = Logger.getLogger(MqttOutputConnector::class.java)
    private var connected = false
    
    override fun connect(config: Map<String, Any>) {
        val host = config["host"] as? String ?: "localhost"
        val port = (config["port"] as? Number)?.toInt() ?: 1883
        
        LOG.infof("MQTT output connecting to %s:%d (stub)", host, port)
        connected = true
    }
    
    override fun write(data: List<Map<String, Any>>) {
        if (!connected) return
        LOG.debugf("MQTT stub wrote %d messages", data.size)
    }
    
    override fun writeOne(data: Map<String, Any>) {
        if (!connected) return
        LOG.debugf("MQTT stub wrote 1 message")
    }
    
    override fun disconnect() {
        connected = false
    }
    
    override fun isConnected(): Boolean = connected
}
