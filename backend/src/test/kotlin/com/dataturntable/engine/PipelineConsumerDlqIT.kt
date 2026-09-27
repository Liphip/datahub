package com.dataturntable.engine

import com.dataturntable.events.ConsumerDeadLetter
import com.dataturntable.test.KafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.common.QuarkusTestResource
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The `%test` profile in `application.yaml` disables both `dataturntable.runtime.background-enabled`
 * and `mp.messaging.incoming.pipeline-input.enabled` by default (correct for the rest of the suite,
 * which doesn't want the pipeline Kafka consumer wired up). `PipelineConsumer.consume()` short-
 * circuits to "skipped" whenever `RuntimeRoleConfig.ownsPipelineRuntime()` is false, which it always
 * is under those defaults — so this test's own app-level consumer never fired at all, regardless of
 * message delivery. Root-caused this session (previously mislabeled "flaky" with no explanation):
 * the message never reached `PipelineConsumer.consume()` in any run, not merely late. Both settings
 * must be explicitly re-enabled for this specific test.
 */
class PipelineConsumerDlqTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "dataturntable.runtime.background-enabled" to "true",
        "mp.messaging.incoming.pipeline-input.enabled" to "true"
    )
}

@QuarkusTest
@TestProfile(PipelineConsumerDlqTestProfile::class)
@QuarkusTestResource(KafkaTestResource::class, restrictToAnnotatedClass = true)
class PipelineConsumerDlqIT {
    @PersistenceContext
    lateinit var em: EntityManager

    @Test
    fun `malformed pipeline message is recorded to consumer_dead_letters`() {
        val bootstrap = System.getProperty("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092"
        val topic = "dataturntable.pipelines.input"

        // Root-caused this session: a single fire-and-forget produce right after container
        // startup races the reactive-messaging consumer's own group-join/partition-assignment,
        // which can still be in progress moments after "dataturntable ... started" logs (observed
        // directly: the consumer's "Received pipeline message" log line never appeared at all
        // within the old 15s window, meaning the message was never delivered to the consumer, not
        // that dead-lettering itself failed). Pre-creating the topic via AdminClient (matching the
        // working pattern in EventOutboxReplayE2EIT) and retry-sending until a dead-letter record
        // appears makes this deterministic instead of racing consumer startup.
        val adminProps = Properties().apply { this["bootstrap.servers"] = bootstrap }
        AdminClient.create(adminProps).use { admin ->
            runCatching { admin.createTopics(listOf(NewTopic(topic, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS) }
        }

        val producerProps = Properties().apply {
            this["bootstrap.servers"] = bootstrap
            this["key.serializer"] = StringSerializer::class.java.name
            this["value.serializer"] = StringSerializer::class.java.name
        }

        val deadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis()
        var found = false
        var attempt = 0
        KafkaProducer<String, String>(producerProps).use { producer ->
            while (System.currentTimeMillis() < deadline && !found) {
                attempt++
                producer.send(ProducerRecord(topic, "key-$attempt", "not-a-json")).get(10, TimeUnit.SECONDS)
                val pollDeadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis()
                while (System.currentTimeMillis() < pollDeadline && !found) {
                    Thread.sleep(250)
                    val list = em.createQuery("SELECT d FROM ConsumerDeadLetter d ORDER BY d.createdAt DESC", ConsumerDeadLetter::class.java)
                        .setMaxResults(10).resultList
                    if (list.any { it.payload.contains("not-a-json") || it.error.isNotBlank() }) found = true
                }
            }
        }

        assertTrue(found, "Expected a consumer_dead_letters record for the malformed message after $attempt send attempt(s)")
    }
}
