package com.dataturntable.events

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "consumer_dead_letters")
class ConsumerDeadLetter {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(nullable = false) var channel: String = ""
    @Column(nullable = false, columnDefinition = "text") var payload: String = ""
    @Column(nullable = false, columnDefinition = "text") var error: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
