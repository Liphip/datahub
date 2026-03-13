package com.dataturntable.connectors

import com.dataturntable.connectors.http.HttpConnector
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class ConnectorManagerTest {
    
    @Inject
    lateinit var connectorManager: ConnectorManager
    
    @Test
    fun testGetSupportedInputConnectors() {
        val connectors = connectorManager.getSupportedInputConnectors()
        assertTrue(connectors.contains("mqtt"))
        assertTrue(connectors.contains("http"))
        assertTrue(connectors.contains("rest"))
    }
    
    @Test
    fun testGetSupportedOutputConnectors() {
        val connectors = connectorManager.getSupportedOutputConnectors()
        assertTrue(connectors.contains("mqtt"))
        assertTrue(connectors.contains("http"))
        assertTrue(connectors.contains("rest"))
    }
    
    @Test
    fun testCreateMqttInputConnector() {
        val config = mapOf(
            "host" to "localhost",
            "port" to 1883,
            "topic" to "sensors/#",
            "clientId" to "test-client"
        )
        
        val connector = connectorManager.createInputConnector("mqtt", config)
        assertNotNull(connector)
        assertTrue(connector is com.dataturntable.connectors.mqtt.MqttInputConnector)
    }
    
    @Test
    fun testCreateHttpInputConnector() {
        val config = mapOf(
            "baseUrl" to "http://localhost:8080",
            "url" to "/api/data",
            "method" to "GET"
        )
        
        val connector = connectorManager.createInputConnector("http", config)
        assertNotNull(connector)
        assertTrue(connector is com.dataturntable.connectors.http.HttpInputConnector)
    }
    
    @Test
    fun testCreateMqttOutputConnector() {
        val config = mapOf(
            "host" to "localhost",
            "port" to 1883,
            "topic" to "output/data",
            "clientId" to "test-client-output"
        )
        
        val connector = connectorManager.createOutputConnector("mqtt", config)
        assertNotNull(connector)
        assertTrue(connector is com.dataturntable.connectors.mqtt.MqttOutputConnector)
    }
    
    @Test
    fun testCreateHttpOutputConnector() {
        val config = mapOf(
            "baseUrl" to "http://localhost:8080",
            "url" to "/api/data",
            "method" to "POST"
        )
        
        val connector = connectorManager.createOutputConnector("http", config)
        assertNotNull(connector)
        assertTrue(connector is com.dataturntable.connectors.http.HttpOutputConnector)
    }
    
    @Test
    fun testUnknownInputConnector() {
        val config = emptyMap<String, Any>()
        
        assertThrows(IllegalArgumentException::class.java) {
            connectorManager.createInputConnector("unknown", config)
        }
    }
    
    @Test
    fun testUnknownOutputConnector() {
        val config = emptyMap<String, Any>()
        
        assertThrows(IllegalArgumentException::class.java) {
            connectorManager.createOutputConnector("unknown", config)
        }
    }
}
