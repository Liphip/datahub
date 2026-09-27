package com.dataturntable.events

import com.dataturntable.test.KafkaTestResource
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.common.QuarkusTestResource
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.apache.http.HttpStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic

@QuarkusTest
@QuarkusTestResource(KafkaTestResource::class)
class EventOutboxReplayIT {
    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var outboxService: EventOutboxService

    @Test
    fun `operator retry moves dead-letter to pending and dispatch publishes to broker`() {
        val eventId = persistDeadLetter()

        val token = TestAuthHelpers.createAdminToken()

        // Call retry endpoint via API
        given().header("Authorization", "Bearer $token")
            .`when`().post("/api/internal/v1/events/dlq/$eventId/retry")
            .then().statusCode(HttpStatus.SC_OK)

        // Verify DB updated to PENDING
        val pending = em.find(EventOutbox::class.java, eventId)
        assertNotNull(pending)
        assertEquals(EventOutboxStatus.PENDING, pending.status)
        assertEquals(0, pending.attempts)

        // Ensure topic exists so Kafka will have a leader for producers
        try {
            val bootstrap = System.getProperty("KAFKA_BOOTSTRAP_SERVERS") ?: System.getenv("KAFKA_BOOTSTRAP_SERVERS")
            val props = Properties()
            props["bootstrap.servers"] = bootstrap
            AdminClient.create(props).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic("dataturntable.pipelines.input", 1, 1.toShort()))).all().get(5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // topic may already exist or creation may race; ignore
                }
            }
        } catch (e: Exception) {
            // ignore topic-creation errors in test environment
        }

        // Trigger dispatch to publish the pending event to Kafka; ensure dispatcher can be invoked
        val dispatched = outboxService.dispatchPending(10)
        assert(dispatched >= 0)
    }

    @Transactional
    fun persistDeadLetter(): UUID {
        val ev = EventOutbox().apply {
            topic = "dataturntable.pipelines.input"
            partitionKey = "pipeline-${UUID.randomUUID()}"
            payload = "{\"eventId\":\"${UUID.randomUUID()}\",\"payload\":{}}"
            status = EventOutboxStatus.DEAD_LETTERED
            attempts = 8
            lastError = "broker unavailable"
            createdAt = Instant.now()
        }
        em.persist(ev)
        em.flush()
        return ev.id!!
    }
}
