package com.dataturntable.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IngestRateLimiterTest {
    @Test
    fun `credential is rejected after its configured minute quota`() {
        val limiter = IngestRateLimiter()
        repeat(3) { assertTrue(limiter.tryAcquire("credential", limitPerMinute = 3)) }
        assertFalse(limiter.tryAcquire("credential", limitPerMinute = 3))
        assertTrue(limiter.tryAcquire("different-credential", limitPerMinute = 3))
    }
}
