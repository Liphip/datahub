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
}
