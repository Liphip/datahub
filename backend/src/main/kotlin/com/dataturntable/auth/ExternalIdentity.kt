package com.dataturntable.auth

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** An administrator-approved OIDC subject; email claims never grant local account access. */
@Entity
@Table(name = "external_identities", uniqueConstraints = [UniqueConstraint(columnNames = ["issuer", "subject"])])
open class ExternalIdentity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    @Column(nullable = false, length = 512)
    var issuer: String = ""
    @Column(nullable = false, length = 255)
    var subject: String = ""
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User? = null
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

/** Short-lived browser-bound handoff, shared by control API replicas. Only hashes are stored. */
@Entity
@Table(name = "oidc_login_tickets")
open class OidcLoginTicket {
    @Id @Column(name = "code_hash", length = 64)
    var codeHash: String = ""
    @Column(name = "browser_hash", nullable = false, length = 64)
    var browserHash: String = ""
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_id", nullable = false)
    var externalIdentity: ExternalIdentity? = null
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()
}
