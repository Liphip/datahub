package com.dataturntable.health

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class HealthCheckTest {
    
    @Inject
    lateinit var databaseHealthCheck: DatabaseHealthCheck
    
    @Inject
    lateinit var kafkaHealthCheck: KafkaHealthCheck
    
    @Inject
    lateinit var livenessHealthCheck: LivenessHealthCheck
    
    @Test
    fun testDatabaseHealthCheckExists() {
        assertNotNull(databaseHealthCheck)
    }
    
    @Test
    fun testKafkaHealthCheckExists() {
        assertNotNull(kafkaHealthCheck)
    }
    
    @Test
    fun testLivenessHealthCheckExists() {
        assertNotNull(livenessHealthCheck)
    }
    
    @Test
    fun testLivenessHealthCheck() {
        val response = livenessHealthCheck.call()
        assertNotNull(response)
        assertEquals("memory", response.name)
    }
}
