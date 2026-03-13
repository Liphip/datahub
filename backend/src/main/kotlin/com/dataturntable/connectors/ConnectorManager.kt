package com.dataturntable.connectors

import com.dataturntable.connectors.http.HttpConnector
import com.dataturntable.connectors.mqtt.MqttConnector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class ConnectorManager {
    
    @Inject
    lateinit var mqttConnector: MqttConnector
    
    @Inject
    lateinit var httpConnector: HttpConnector
    
    fun createInputConnector(type: String, config: Map<String, Any>): InputConnector {
        return when (type.lowercase()) {
            "mqtt" -> mqttConnector.connectAsInput(config)
            "http", "rest" -> httpConnector.createRestInput(config)
            else -> throw IllegalArgumentException("Unknown input connector type: $type")
        }
    }
    
    fun createOutputConnector(type: String, config: Map<String, Any>): OutputConnector {
        return when (type.lowercase()) {
            "mqtt" -> mqttConnector.connectAsOutput(config)
            "http", "rest" -> httpConnector.createRestOutput(config)
            else -> throw IllegalArgumentException("Unknown output connector type: $type")
        }
    }
    
    fun getSupportedInputConnectors(): List<String> = listOf("mqtt", "http", "rest")
    
    fun getSupportedOutputConnectors(): List<String> = listOf("mqtt", "http", "rest")
}
