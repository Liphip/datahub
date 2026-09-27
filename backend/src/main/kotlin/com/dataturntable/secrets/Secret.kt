package com.dataturntable.secrets

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** Tenant-owned secret identity. Values only exist in immutable SecretVersion rows. */
@Entity
@Table(name = "secrets", uniqueConstraints = [UniqueConstraint(columnNames = ["tenant_id", "name"])])
class Secret {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID? = null
    @Column(nullable = false, length = 128) var name: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
    @Column(name = "deleted_at") var deletedAt: Instant? = null
}
