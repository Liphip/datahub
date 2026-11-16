package com.dataturntable.connectors.polling

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** CON-13: the operator-visible next-run time comes from the scheduler itself, not from a guess. */
class ConnectorPollingInfoTest {
    @Test
    fun `a scheduled connector reports when it polls next and an unscheduled one reports nothing`() {
        val service = ConnectorPollingService()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val id = UUID.randomUUID()
        try {
            assertNull(service.getPollingInfo(id).nextRunAt)
            assertFalse(service.getPollingInfo(id).scheduled)

            service.scheduledTasks[id] = scheduler.scheduleAtFixedRate({}, 120, 120, TimeUnit.SECONDS)
            val info = service.getPollingInfo(id)
            assertTrue(info.scheduled)
            val next = info.nextRunAt ?: error("a scheduled connector must report its next run")
            val inSeconds = Duration.between(Instant.now(), next).seconds
            assertTrue(inSeconds in 110..120, "next run should be about one interval away, was ${inSeconds}s")

            service.scheduledTasks[id]!!.cancel(false)
            assertNull(service.getPollingInfo(id).nextRunAt, "a cancelled schedule has no next run")
        } finally {
            scheduler.shutdownNow()
        }
    }
}
