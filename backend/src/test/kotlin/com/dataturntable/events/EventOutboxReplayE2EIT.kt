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
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic

@QuarkusTest
@QuarkusTestResource(KafkaTestResource::class)
class EventOutboxReplayE2EIT {
    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var outboxService: EventOutboxService

    @Test
    fun `e2e retry publishes event to broker and consumer receives it`() {
        // Use a unique topic so the platform's pipeline consumer does not pre-consume the message
            val uniqueTopic = "dataturntable.pipelines.input.e2e." + UUID.randomUUID().toString()
        val eventId = persistDeadLetter(uniqueTopic)

        val token = TestAuthHelpers.createAdminToken()

        // Ensure topic exists so Kafka will have a leader for producers/consumers
        try {
            val bootstrap = System.getProperty("KAFKA_BOOTSTRAP_SERVERS") ?: System.getenv("KAFKA_BOOTSTRAP_SERVERS")
            val props = Properties()
            props["bootstrap.servers"] = bootstrap
            AdminClient.create(props).use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(uniqueTopic, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // topic may already exist or creation may race; ignore
                }
            }
        } catch (e: Exception) {
            // ignore topic-creation errors in test environment
        }

        // Start a consumer to verify delivery
        val bootstrap = System.getProperty("KAFKA_BOOTSTRAP_SERVERS") ?: System.getenv("KAFKA_BOOTSTRAP_SERVERS")
        val consumerProps = Properties()
        consumerProps[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrap
        consumerProps[ConsumerConfig.GROUP_ID_CONFIG] = "e2e-consumer-${UUID.randomUUID()}"
        consumerProps[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        consumerProps[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        consumerProps[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"

        KafkaConsumer<String, String>(consumerProps).use { consumer ->
            consumer.subscribe(listOf(uniqueTopic))

            // Call retry endpoint via API
            given().header("Authorization", "Bearer $token")
                .`when`().post("/api/internal/v1/events/dlq/" + eventId.toString() + "/retry")
                .then().statusCode(HttpStatus.SC_OK)

            // Verify DB updated to PENDING
            val pending = em.find(EventOutbox::class.java, eventId)
            assertNotNull(pending)
            assertEquals(EventOutboxStatus.PENDING, pending.status)

            // Trigger dispatch to publish the pending event to Kafka
            val dispatched = outboxService.dispatchPending(10)
            assert(dispatched >= 0)

            // Poll the consumer for the published record
            val deadline = Instant.now().plusSeconds(30)
            var found = false
            while (Instant.now().isBefore(deadline) && !found) {
                val recs = consumer.poll(Duration.ofMillis(1000))
                recs.forEach { r ->
                    if (r.value().contains(eventId.toString())) {
                        found = true
                    }
                }
            }

            assert(found)
        }
    }

    @Transactional
    fun persistDeadLetter(topic: String): UUID {
        val ev = EventOutbox().apply {
            this.topic = topic
            partitionKey = "pipeline-" + UUID.randomUUID().toString()
            // payload will include the DB id; set after persist
            payload = ""
            status = EventOutboxStatus.DEAD_LETTERED
            attempts = 8
            lastError = "broker unavailable"
            createdAt = Instant.now()
        }
        em.persist(ev)
        em.flush()
        // now that id is assigned, update payload to include the DB id so the E2E
        // consumer can assert on the same identifier
        ev.payload = "{\"eventId\":\"${ev.id}\",\"payload\":{}}"
        em.flush()
        return ev.id!!
    }
}
