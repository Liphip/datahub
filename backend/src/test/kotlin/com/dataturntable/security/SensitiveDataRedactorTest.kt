package com.dataturntable.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitiveDataRedactorTest {
    private val redactor = SensitiveDataRedactor()

    @Test
    fun `operational output redacts credential-shaped values`() {
        val output = redactor.text("password=correct-horse token: abc123 Authorization: Bearer private.jwt https://alice:secret@example.com/path")!!

        assertFalse(output.contains("correct-horse"))
        assertFalse(output.contains("abc123"))
        assertFalse(output.contains("private.jwt"))
        assertFalse(output.contains("alice:secret"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun `structured values redact sensitive keys recursively`() {
        val redacted = redactor.value(mapOf("outer" to mapOf("apiKey" to "hidden"), "safe" to "visible")) as Map<*, *>
        val outer = redacted["outer"] as Map<*, *>

        assertTrue(outer["apiKey"] == "[REDACTED]")
        assertTrue(redacted["safe"] == "visible")
    }

    @Test
    fun `redacts credentials embedded in non-HTTP broker URLs`() {
        // MqttConnectionManager/WebSocketConnectionManager log connector destination URLs on
        // connect/publish/failure; MQTT brokers commonly embed credentials as tcp://user:pass@host.
        val mqttMessage = redactor.text("MQTT connector abc failed to connect to tcp://device:hunter2@broker.example.com:1883: Connection refused")!!
        assertFalse(mqttMessage.contains("device:hunter2"))
        assertTrue(mqttMessage.contains("[REDACTED]@broker.example.com"))

        val wsMessage = redactor.text("WebSocket connector xyz connected to wss://svc:s3cr3t@ws.example.com/stream")!!
        assertFalse(wsMessage.contains("svc:s3cr3t"))
        assertTrue(wsMessage.contains("[REDACTED]@ws.example.com"))
    }

    @Test
    fun `redacts API keys embedded in webhook or alert-channel URL query strings`() {
        // AlertDispatcher logs e.message from failed WEBHOOK/REST/SLACK dispatch attempts, and
        // channel configs commonly embed an API key as a query parameter rather than URL userinfo.
        val message = redactor.text(
            "Connection refused: https://hooks.example.com/notify?api_key=sk_live_abc123&channel=ops"
        )!!
        assertFalse(message.contains("sk_live_abc123"))
        assertTrue(message.contains("[REDACTED]"))
    }
}
