package com.dataturntable.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Append-only security/administrative event; metadata is always redacted before persistence. */
@Entity
@Table(name = "audit_events")
class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "occurred_at", nullable = false) var occurredAt: Instant = Instant.now()
    @Column(name = "actor_id") var actorId: UUID? = null
    @Column(name = "tenant_id") var tenantId: UUID? = null
    @Column(nullable = false, length = 128) var action: String = ""
    @Column(name = "resource_type", nullable = false, length = 128) var resourceType: String = ""
    @Column(name = "resource_id", length = 255) var resourceId: String? = null
    @Column(nullable = false, length = 32) var outcome: String = "SUCCESS"
    @Column(name = "request_id", length = 128) var requestId: String? = null
    @Column(columnDefinition = "TEXT") var metadata: String? = null

    /** Position in the hash chain (AUD-05); null on records written before the chain existed. */
    @Column(unique = true) var seq: Long? = null
    @Column(name = "prev_hash", length = 64) var prevHash: String? = null
    @Column(length = 64) var hash: String? = null
}

/** The chain's single head row; locked while a record is appended. */
@Entity
@Table(name = "audit_chain_head")
class AuditChainHead {
    @Id var id: Short = 1
    @Column(name = "last_seq", nullable = false) var lastSeq: Long = 0
    @Column(name = "last_hash", nullable = false, length = 64) var lastHash: String = ""
}

/** AUD-06: an immutable note on an audit record or on a component's timeline. */
@Entity
@Table(name = "audit_notes")
class AuditNote {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "event_id") var eventId: UUID? = null
    @Column(name = "tenant_id") var tenantId: UUID? = null
    @Column(name = "resource_type", length = 128) var resourceType: String? = null
    @Column(name = "resource_id", length = 255) var resourceId: String? = null
    @Column(name = "author_id") var authorId: UUID? = null
    @Column(nullable = false, columnDefinition = "TEXT") var note: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
