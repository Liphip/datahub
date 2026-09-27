package com.dataturntable.events

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "event_inbox")
class EventInbox {
    @Id
    @Column(name = "event_id")
    var eventId: UUID? = null
    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant = Instant.now()
}
