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

/** Immutable encrypted value for a tenant secret. Never expose ciphertext through normal APIs. */
@Entity
@Table(name = "secret_versions", uniqueConstraints = [UniqueConstraint(columnNames = ["secret_id", "version"])])
class SecretVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "secret_id", nullable = false) var secretId: UUID? = null
    @Column(nullable = false) var version: Int = 0
    @Column(name = "value_encrypted", nullable = false, columnDefinition = "TEXT") var valueEncrypted: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
