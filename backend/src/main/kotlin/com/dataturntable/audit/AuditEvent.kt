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
}
