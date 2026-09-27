package com.dataturntable.alert

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

@QuarkusTest
class AlertCooldownServiceTest {
    @Inject lateinit var cooldowns: AlertCooldownService

    @Test
    fun `only one replica decision wins a shared cooldown window`() {
        val ruleId = UUID.randomUUID()
        val now = Instant.parse("2026-08-17T20:00:00Z")
        cooldowns.initialize(ruleId)

        assertTrue(cooldowns.tryAcquire(ruleId, 60, now))
        assertFalse(cooldowns.tryAcquire(ruleId, 60, now.plusSeconds(1)))
        assertTrue(cooldowns.tryAcquire(ruleId, 60, now.plusSeconds(60)))
    }

    @Test
    fun `zero cooldown does not suppress delivery`() {
        val ruleId = UUID.randomUUID()
        val now = Instant.parse("2026-08-17T20:00:00Z")
        cooldowns.initialize(ruleId)

        assertTrue(cooldowns.tryAcquire(ruleId, 0, now))
        assertTrue(cooldowns.tryAcquire(ruleId, 0, now))
    }

    @Test
    fun `simultaneous replica contenders have exactly one cooldown winner`() {
        val ruleId = UUID.randomUUID()
        val now = Instant.parse("2026-08-17T20:00:00Z")
        cooldowns.initialize(ruleId)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val decisions = (1..2).map {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    cooldowns.tryAcquire(ruleId, 60, now)
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val winners = decisions.count { it.get(10, TimeUnit.SECONDS) }
            assertTrue(winners == 1, "exactly one replica must acquire the shared cooldown")
        } finally {
            executor.shutdownNow()
        }
    }
}
