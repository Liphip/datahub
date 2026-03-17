package com.dataturntable.alert

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class AlertDispatcherTest {
    
    @Inject
    lateinit var alertDispatcher: AlertDispatcher
    
    @Test
    fun testAlertDispatcherInitialization() {
        assertNotNull(alertDispatcher)
    }
    
    @Test
    fun testAlertsEnabledByDefault() {
        assertTrue(alertDispatcher.alertsEnabled)
    }

    @Test
    fun testMatchesConditionWithNumericOperator() {
        val payload = mapOf("temperature" to 86)
        val condition = """{"field":"temperature","operator":">","value":80}"""
        assertTrue(alertDispatcher.matchesCondition(condition, payload))
    }

    @Test
    fun testMatchesConditionWithNestedFieldPath() {
        val payload = mapOf("sensor" to mapOf("humidity" to 41))
        val condition = """{"field":"sensor.humidity","operator":"<=","value":50}"""
        assertTrue(alertDispatcher.matchesCondition(condition, payload))
    }

    @Test
    fun testMatchesConditionWithAnyClause() {
        val payload = mapOf("temperature" to 28, "status" to "WARN")
        val condition = """
            {"any":[
              {"field":"temperature","operator":">","value":80},
              {"field":"status","operator":"==","value":"WARN"}
            ]}
        """.trimIndent()
        assertTrue(alertDispatcher.matchesCondition(condition, payload))
    }
}
