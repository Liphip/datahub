package com.dataturntable.events

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class EventOutboxServiceTest {
    @Inject lateinit var outbox: EventOutboxService
    @Inject lateinit var em: EntityManager

    @Test
    @Transactional
    fun `persists broker work before dispatch`() {
        val partitionKey = "pipeline-${UUID.randomUUID()}"
        outbox.enqueue("dataturntable.pipelines.input", partitionKey, "{\"eventId\":\"test\"}")
        em.flush()

        val record = em.createQuery(
            "SELECT e FROM EventOutbox e WHERE e.partitionKey = :partitionKey",
            EventOutbox::class.java
        ).setParameter("partitionKey", partitionKey).singleResult
        assertEquals(EventOutboxStatus.PENDING, record.status)
        assertEquals(partitionKey, record.partitionKey)
        assertNotNull(record.id)
    }

    @Test
    fun `a broker handoff moves to DLQ after its finite retry budget`() {
        val service = EventOutboxService().apply { maxAttempts = 3 }
        val event = EventOutbox().apply { attempts = 2 }
        service.recordFailure(event, IllegalStateException("broker unavailable"))

        assertEquals(EventOutboxStatus.DEAD_LETTERED, event.status)
        assertEquals(3, event.attempts)
        assertEquals("broker unavailable", event.lastError)
    }
}
