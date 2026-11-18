package com.dataturntable.connectors.mqtt

import com.dataturntable.security.SensitiveDataRedactor
import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ConnectException

class MqttConnectionDiagnosticsTest {
    private val manager = MqttConnectionManager().apply { redactor = SensitiveDataRedactor() }

    @Test
    fun `shows Paho reason code and actual network cause`() {
        val error = manager.connectionError(MqttException(ConnectException("Connection refused")))
        assertTrue(error.contains("reasonCode="))
        assertTrue(error.contains("ConnectException: Connection refused"))
    }

    @Test
    fun `diagnostics redact credentials in nested causes`() {
        val error = manager.connectionError(MqttException(IllegalArgumentException("tcp://user:private@broker password=hidden")))
        assertFalse(error.contains("user:private"))
        assertFalse(error.contains("hidden"))
        assertTrue(error.contains("[REDACTED]"))
    }

    @Test
    fun `diagnostics are bounded and stay on one log line`() {
        val error = manager.connectionError(IllegalArgumentException("first\nsecond\r" + "x".repeat(4000)))
        assertTrue(error.length <= 2048)
        assertFalse(error.contains('\n'))
        assertFalse(error.contains('\r'))
    }
}
