package com.dataturntable.alert

import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Defines the enum class `AlertSeverity`, the contract implemented by this source unit.
 */
enum class AlertSeverity {
    INFO, WARNING, CRITICAL, ERROR
}

/**
 * Defines the enum class `AlertStatus`, the contract implemented by this source unit.
 */
enum class AlertStatus {
    /** In the outbox, waiting for (another) delivery attempt. */
    PENDING,
    /** Claimed by a replica that is delivering it right now. */
    SENDING,
    SENT,
    /** Not sent: the same alert was sent to the channel within the cooldown. */
    SUPPRESSED,
    /** Delivery attempts exhausted. */
    FAILED,
}

@Entity
@Table(name = "alert_history", uniqueConstraints = [UniqueConstraint(columnNames = ["delivery_key"])])
/**
 * Defines the class `AlertHistory`, the contract implemented by this source unit.
 */
open class AlertHistory : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    var channel: AlertChannel? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id")
    var pipeline: com.dataturntable.pipeline.Pipeline? = null

    /** Identifies "the same alert" (pipeline, node, title) for cooldown suppression. */
    @Column(name = "dedup_key", length = 512)
    var dedupKey: String? = null

    /**
     * Identity of this alert in the outbox (e.g. run, node and ordinal). Unique, so work
     * that is repeated after a failover cannot enqueue the alert twice.
     */
    @Column(name = "delivery_key", length = 600)
    var deliveryKey: String? = null
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var severity: AlertSeverity? = null
    
    @Column(nullable = false)
    var title: String? = null
    
    @Column(columnDefinition = "text")
    var message: String? = null
    
    @Column(columnDefinition = "text")
    var payload: String? = null
    
    /** When the alert was raised. */
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    /** When a channel accepted it; null until then. */
    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AlertStatus = AlertStatus.PENDING
    
    companion object : PanacheCompanionBase<AlertHistory, UUID> {
        override fun findById(id: UUID): AlertHistory? =
            getEntityManager()
                .createQuery("SELECT a FROM AlertHistory a WHERE a.id = :id", AlertHistory::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findRecentByTenant(tenantId: UUID, limit: Int = 100): List<AlertHistory> =
            getEntityManager()
                .createQuery(
                    "SELECT a FROM AlertHistory a WHERE a.tenant.id = :tenantId ORDER BY a.createdAt DESC",
                    AlertHistory::class.java
                )
                .setParameter("tenantId", tenantId)
                .setMaxResults(limit)
                .resultList
    }
}
