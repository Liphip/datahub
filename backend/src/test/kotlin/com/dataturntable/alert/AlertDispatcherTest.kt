package com.dataturntable.alert

import com.dataturntable.alert.AlertChannelType.*
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class AlertDispatcherTest {
    
    @Inject
    lateinit var alertDispatcher: AlertDispatcher
    
    @Test
    @Transactional
    fun testAlertDispatcherInitialization() {
        assertNotNull(alertDispatcher)
    }
    
    @Test
    fun testMqttAlertSenderCreation() {
        val sender = MqttAlertSender()
        assertNotNull(sender)
    }
    
    @Test
    fun testWebhookAlertSenderCreation() {
        val sender = WebhookAlertSender()
        assertNotNull(sender)
    }
    
    @Test
    fun testRestAlertSenderCreation() {
        val sender = RestAlertSender()
        assertNotNull(sender)
    }
    
    @Test
    fun testEmailAlertSenderCreation() {
        val sender = EmailAlertSender()
        assertNotNull(sender)
    }
    
    @Test
    fun testParseConfigWithValidJson() {
        val config = """{"url": "https://example.com", "port": 8080}"""
        val result = parseConfig(config)
        assertEquals("https://example.com", result["url"])
        assertEquals(8080, result["port"])
    }
    
    @Test
    fun testParseConfigWithEmptyString() {
        val result = parseConfig("")
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun testParseConfigWithNull() {
        val result = parseConfig(null)
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun testParseConfigWithInvalidJson() {
        val config = "not valid json"
        val result = parseConfig(config)
        assertTrue(result.isEmpty())
    }
}
