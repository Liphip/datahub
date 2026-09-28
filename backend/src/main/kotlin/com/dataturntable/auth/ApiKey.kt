package com.dataturntable.auth

import com.dataturntable.PanacheBase
import com.dataturntable.tenant.Tenant
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** A non-interactive credential used only by explicitly granted ingestion surfaces. */
@Entity
@Table(name = "api_keys", uniqueConstraints = [UniqueConstraint(columnNames = ["tenant_id", "name"])])
class ApiKey : PanacheBase() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    var tenant: Tenant? = null

    @Column(nullable = false)
    var name: String? = null

    @Column(name = "key_hash", nullable = false)
    var keyHash: String? = null

    /** JSON array of exact grants, e.g. ingest:pipeline:<uuid>. */
    @Column(nullable = false, columnDefinition = "text")
    var abilities: String = "[]"

    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
