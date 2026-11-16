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

    @Test
    fun `a tenant quota is shared by all its senders, is all-or-nothing for a batch and names the wait`() {
        val limiter = IngestRateLimiter()
        val tenant = java.util.UUID.randomUUID()
        val other = java.util.UUID.randomUUID()

        assertTrue(limiter.tenantRetryAfter(tenant, 5, records = 3) == 0)
        // 3 used, a batch of 3 does not fit and must not consume the 2 that are left.
        assertTrue(limiter.tenantRetryAfter(tenant, 5, records = 3) in 1..60)
        assertTrue(limiter.tenantRetryAfter(tenant, 5, records = 2) == 0)
        assertTrue(limiter.tenantRetryAfter(tenant, 5) in 1..60)
        // One tenant using up its quota does not delay another.
        assertTrue(limiter.tenantRetryAfter(other, 5) == 0)
    }

    @Test
    fun `without a tenant quota or installation default nothing is limited`() {
        val limiter = IngestRateLimiter()
        repeat(1000) { assertTrue(limiter.tenantRetryAfter(java.util.UUID.randomUUID(), null) == 0) }
        limiter.configuredTenantLimit = 1
        val tenant = java.util.UUID.randomUUID()
        assertTrue(limiter.tenantRetryAfter(tenant, null) == 0)
        assertFalse(limiter.tenantRetryAfter(tenant, null) == 0, "the installation default applies when the tenant has none")
        assertTrue(limiter.tenantRetryAfter(tenant, 10) == 0, "the tenant's own quota overrides the default")
    }
}
