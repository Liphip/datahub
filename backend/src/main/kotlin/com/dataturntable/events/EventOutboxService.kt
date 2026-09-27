package com.dataturntable.events

import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Supplier

@ApplicationScoped
class EventOutboxService {
    @PersistenceContext lateinit var em: EntityManager

    @Channel("pipeline-commands")
    lateinit var emitter: Emitter<String>

    @ConfigProperty(name = "dataturntable.outbox.max-attempts", defaultValue = "8")
    var maxAttempts: Int = 8

    /** Must be called in the same transaction as the state change that created the event. */
    @Transactional
    fun enqueue(topic: String, partitionKey: String, payload: String) {
        em.persist(EventOutbox().apply {
            this.topic = topic
            this.partitionKey = partitionKey
            this.payload = payload
        })
    }

    /**
     * Publishes a bounded batch. Row locks make multiple dispatcher replicas safe: a row
     * remains locked until its Kafka acknowledgement has either succeeded or failed.
     */
    @Transactional
    fun dispatchPending(limit: Int = 50): Int {
        val now = Instant.now()
        val records = em.createQuery(
            "SELECT e FROM EventOutbox e WHERE e.status = :status AND e.nextAttemptAt <= :now ORDER BY e.createdAt",
            EventOutbox::class.java
        )
            .setParameter("status", EventOutboxStatus.PENDING)
            .setParameter("now", now)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setMaxResults(limit)
            .resultList

        records.forEach(::publish)
        return records.size
    }

    @Transactional
    fun deadLetters(limit: Int = 100): List<EventOutbox> = em.createQuery(
        "SELECT e FROM EventOutbox e WHERE e.status = :status ORDER BY e.createdAt DESC",
        EventOutbox::class.java
    )
        .setParameter("status", EventOutboxStatus.DEAD_LETTERED)
        .setMaxResults(limit.coerceIn(1, 500))
        .resultList

    /** Explicit operator action; retries retain the original payload and partition key. */
    @Transactional
    fun retryDeadLetter(id: java.util.UUID): EventOutbox? {
        val event = em.find(EventOutbox::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: return null
        if (event.status != EventOutboxStatus.DEAD_LETTERED) return null
        event.status = EventOutboxStatus.PENDING
        event.attempts = 0
        event.lastError = null
        event.nextAttemptAt = Instant.now()
        return event
    }

    private fun publish(event: EventOutbox) {
        try {
            val metadata = OutgoingKafkaRecordMetadata.builder<String>()
                .withTopic(event.topic)
                .withKey(event.partitionKey)
                .build()
            val acknowledged = CompletableFuture<Void>()
            val message = Message.of(
                event.payload,
                Metadata.of(metadata),
                Supplier { acknowledged.complete(null); CompletableFuture.completedFuture(null) },
                Function { error: Throwable -> acknowledged.completeExceptionally(error); CompletableFuture.completedFuture(null) }
            )
            emitter.send(message)
            acknowledged.get(5, TimeUnit.SECONDS)
            event.status = EventOutboxStatus.PUBLISHED
            event.publishedAt = Instant.now()
            event.lastError = null
        } catch (error: Exception) {
            recordFailure(event, error)
        }
    }

    internal fun recordFailure(event: EventOutbox, error: Throwable) {
        event.attempts += 1
        event.lastError = error.message?.take(2_000) ?: error.javaClass.simpleName
        if (event.attempts >= maxAttempts.coerceAtLeast(1)) {
            event.status = EventOutboxStatus.DEAD_LETTERED
            return
        }
        // Exponential backoff is capped, keeping failures observable without hot-looping.
        event.nextAttemptAt = Instant.now().plusSeconds(1L shl minOf(event.attempts, 6))
    }
}
