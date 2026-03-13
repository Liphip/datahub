package com.dataturntable.connectors.mqtt

import com.dataturntable.connectors.InputConnector
import com.dataturntable.connectors.OutputConnector
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.mqtt.MqttClient
import io.vertx.mutiny.mqtt.MqttClientOptions
import io.vertx.mutiny.mqtt.messages.MqttPublishMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class MqttConnector {
    
    private val LOG = Logger.getLogger(MqttConnector::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var vertx: Vertx
    
    private var client: MqttClient? = null
    private var config: Map<String, Any> = emptyMap()
    
    fun connectAsInput(config: Map<String, Any>): MqttInputConnector {
        this.config = config
        return MqttInputConnector(config, vertx)
    }
    
    fun connectAsOutput(config: Map<String, Any>): MqttOutputConnector {
        this.config = config
        return MqttOutputConnector(config, vertx)
    }
}

class MqttInputConnector(
    private val config: Map<String, Any>,
    private val vertx: Vertx
) : InputConnector {
    
    private val LOG = Logger.getLogger(MqttInputConnector::class.java)
    private val objectMapper = ObjectMapper()
    private var client: MqttClient? = null
    private val messages = mutableListOf<Map<String, Any>>()
    private val messageLock = Object()
    
    override fun connect(config: Map<String, Any>) {
        val host = config["host"] as? String ?: "localhost"
        val port = (config["port"] as? Number)?.toInt() ?: 1883
        val topic = config["topic"] as? String ?: "#"
        val qos = (config["qos"] as? Number)?.toInt() ?: 0
        
        LOG.infof("Connecting to MQTT broker at %s:%d", host, port)
        
        val options = MqttClientOptions()
            .setHost(host)
            .setPort(port)
            .setClientId(config["clientId"] as? String ?: "dataturntable-${System.currentTimeMillis()}")
            .setUsername(config["username"] as? String)
            .setPassword(config["password"] as? String)
            .setCleanSession(true)
            .setAutoKeepAlive(true)
        
        client = vertx.mqttClient(options)
        
        client?.connect?.invoke { _ ->
            LOG.infof("Connected to MQTT broker, subscribing to topic: %s", topic)
            client?.subscribe(topic, qos)?.invoke { _ ->
                LOG.infof("Subscribed to topic: %s", topic)
            }
        }
        
        client?.publishHandler?.invoke { message ->
            handleMessage(message)
        }
    }
    
    private fun handleMessage(message: MqttPublishMessage) {
        try {
            val payload = message.payload().toString()
            val data = try {
                objectMapper.readValue(payload, Map::class.java) as? Map<String, Any>
            } catch (e: Exception) {
                mapOf("raw" to payload)
            }
            
            val enrichedData = data?.toMutableMap() ?: mutableMapOf()
            enrichedData["_topic"] = message.topicName()
            enrichedData["_qos"] = message.qosLevel().value()
            
            synchronized(messageLock) {
                messages.add(enrichedData)
            }
        } catch (e: Exception) {
            LOG.error("Error processing MQTT message", e)
        }
    }
    
    override fun read(): List<Map<String, Any>> {
        synchronized(messageLock) {
            val result = messages.toList()
            messages.clear()
            return result
        }
    }
    
    override fun readOne(): Map<String, Any>? {
        synchronized(messageLock) {
            return if (messages.isNotEmpty()) messages.removeAt(0) else null
        }
    }
    
    override fun disconnect() {
        client?.disconnect?.invoke { }
        client = null
    }
    
    override fun isConnected(): Boolean = client != null
    
    fun observe(): Multi<Map<String, Any>> {
        return Multi.createFrom().emitter { emitter ->
            client?.publishHandler?.invoke { message ->
                try {
                    val payload = message.payload().toString()
                    val data = try {
                        objectMapper.readValue(payload, Map::class.java) as? Map<String, Any>
                    } catch (e: Exception) {
                        mapOf("raw" to payload)
                    }
                    
                    val enrichedData = data?.toMutableMap() ?: mutableMapOf()
                    enrichedData["_topic"] = message.topicName()
                    enrichedData["_qos"] = message.qosLevel().value()
                    
                    emitter.emit(enrichedData)
                } catch (e: Exception) {
                    LOG.error("Error in MQTT observe", e)
                }
            }
        }
    }
}

class MqttOutputConnector(
    private val config: Map<String, Any>,
    private val vertx: Vertx
) : OutputConnector {
    
    private val LOG = Logger.getLogger(MqttOutputConnector::class.java)
    private val objectMapper = ObjectMapper()
    private var client: MqttClient? = null
    
    override fun connect(config: Map<String, Any>) {
        val host = config["host"] as? String ?: "localhost"
        val port = (config["port"] as? Number)?.toInt() ?: 1883
        
        LOG.infof("Connecting to MQTT broker at %s:%d for output", host, port)
        
        val options = MqttClientOptions()
            .setHost(host)
            .setPort(port)
            .setClientId(config["clientId"] as? String ?: "dataturntable-output-${System.currentTimeMillis()}")
            .setUsername(config["username"] as? String)
            .setPassword(config["password"] as? String)
            .setCleanSession(true)
        
        client = vertx.mqttClient(options)
        
        client?.connect?.invoke { _ ->
            LOG.info("Connected to MQTT broker for output")
        }
    }
    
    override fun write(data: List<Map<String, Any>>) {
        val topic = config["topic"] as? String ?: throw IllegalArgumentException("Topic is required")
        val qos = (config["qos"] as? Number)?.toInt() ?: 0
        val retain = config["retain"] as? Boolean ?: false
        
        data.forEach { item ->
            writeOne(item, topic, qos, retain)
        }
    }
    
    override fun writeOne(data: Map<String, Any>) {
        val topic = config["topic"] as? String ?: throw IllegalArgumentException("Topic is required")
        val qos = (config["qos"] as? Number)?.toInt() ?: 0
        val retain = config["retain"] as? Boolean ?: false
        
        writeOne(data, topic, qos, retain)
    }
    
    private fun writeOne(data: Map<String, Any>, topic: String, qos: Int, retain: Boolean) {
        try {
            val payload = objectMapper.writeValueAsString(data)
            
            client?.publish(topic, io.vertx.mutiny.core.buffer.Buffer.buffer(payload), qos, retain, false)
                ?.invoke { LOG.debugf("Published message to %s", topic) }
                ?.onFailure()?.invoke { e ->
                    LOG.errorf(e, "Failed to publish message to %s", topic)
                }
        } catch (e: Exception) {
            LOG.error("Error serializing message", e)
        }
    }
    
    override fun disconnect() {
        client?.disconnect?.invoke { }
        client = null
    }
    
    override fun isConnected(): Boolean = client != null
}
