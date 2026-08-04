package com.dataturntable.grafana

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Defines the enum class `GrafanaSyncScopeType`, the contract implemented by this source unit.
 */
enum class GrafanaSyncScopeType {
    TENANT,
    USER,
    MEMBERSHIP
}

/**
 * Defines the enum class `GrafanaTargetInstance`, the contract implemented by this source unit.
 */
enum class GrafanaTargetInstance {
    DASHBOARD,
    MONITORING
}

/**
 * Defines the enum class `GrafanaSyncStatus`, the contract implemented by this source unit.
 */
enum class GrafanaSyncStatus {
    PENDING,
    SYNCED,
    FAILED
}

@Entity
@Table(
    name = "grafana_sync_states",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_grafana_sync_states_target_scope",
            columnNames = ["target_instance", "scope_type", "scope_id"]
        )
    ]
)
/**
 * Defines the class `GrafanaSyncState`, the contract implemented by this source unit.
 */
open class GrafanaSyncState : PanacheBase() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "target_instance", nullable = false)
    var targetInstance: GrafanaTargetInstance = GrafanaTargetInstance.DASHBOARD

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    var scopeType: GrafanaSyncScopeType = GrafanaSyncScopeType.TENANT

    @Column(name = "scope_id", nullable = false)
    var scopeId: UUID? = null

    @Column(name = "tenant_id")
    var tenantId: UUID? = null

    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "membership_id")
    var membershipId: UUID? = null

    @Column(name = "desired_state", columnDefinition = "text", nullable = false)
    var desiredState: String = "{}"

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    var syncStatus: GrafanaSyncStatus = GrafanaSyncStatus.PENDING

    @Column(name = "last_synced_at")
    var lastSyncedAt: Instant? = null

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<GrafanaSyncState, UUID> {
        override fun findById(id: UUID): GrafanaSyncState? =
            getEntityManager()
                .createQuery("SELECT g FROM GrafanaSyncState g WHERE g.id = :id", GrafanaSyncState::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTargetAndScope(target: GrafanaTargetInstance, scopeType: GrafanaSyncScopeType, scopeId: UUID): GrafanaSyncState? =
            getEntityManager()
                .createQuery(
                    "SELECT g FROM GrafanaSyncState g WHERE g.targetInstance = :target AND g.scopeType = :scopeType AND g.scopeId = :scopeId",
                    GrafanaSyncState::class.java
                )
                .setParameter("target", target)
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .resultList
                .firstOrNull()
    }
}
