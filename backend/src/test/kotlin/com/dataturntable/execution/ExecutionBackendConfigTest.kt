package com.dataturntable.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionBackendConfigTest {
    @Test
    fun `normalizes the configured execution backend`() {
        assertEquals("kubernetes", ExecutionBackendConfig(" Kubernetes ").backend)
    }

    @Test
    fun `rejects unsupported execution backend at startup`() {
        assertThrows(IllegalArgumentException::class.java) { ExecutionBackendConfig("remote-shell") }
    }
}
