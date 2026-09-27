package com.dataturntable.events

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class EventOutboxStatus { PENDING, PUBLISHED, DEAD_LETTERED }

/** A durable broker handoff record. Payloads are retained until acknowledged by Kafka. */
@Entity
@Table(name = "event_outbox")
class EventOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(nullable = false, length = 255)
    var topic: String = ""

    @Column(name = "partition_key", nullable = false, length = 255)
    var partitionKey: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var payload: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: EventOutboxStatus = EventOutboxStatus.PENDING

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now()

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
