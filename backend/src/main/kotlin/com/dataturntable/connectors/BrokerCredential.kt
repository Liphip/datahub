package com.dataturntable.connectors

import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "broker_credentials")
/**
 * Defines the class `BrokerCredential`, the contract implemented by this source unit.
 */
class BrokerCredential : PanacheEntityBase() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    var tenant: com.dataturntable.tenant.Tenant? = null

    @Column(nullable = false, length = 120)
    var name: String? = null

    @Column(nullable = false, length = 120)
    var username: String? = null

    @Column(name = "password_encrypted", nullable = false, columnDefinition = "TEXT")
    var passwordEncrypted: String? = null

    @Column(name = "allowed_topics", nullable = false, columnDefinition = "TEXT")
    var allowedTopics: String = "[]"

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
