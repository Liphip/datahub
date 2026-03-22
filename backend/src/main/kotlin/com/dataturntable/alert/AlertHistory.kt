package com.dataturntable.alert

import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class AlertSeverity {
    INFO, WARNING, CRITICAL, ERROR
}

enum class AlertStatus {
    PENDING, SENT, FAILED, RETRY
}

@Entity
@Table(name = "alert_history")
open class AlertHistory : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    var rule: AlertRule? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    var channel: AlertChannel? = null
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var severity: AlertSeverity? = null
    
    @Column(nullable = false)
    var title: String? = null
    
    @Column(columnDefinition = "text")
    var message: String? = null
    
    @Column(columnDefinition = "text")
    var payload: String? = null
    
    @Column(name = "sent_at")
    var sentAt: Instant = Instant.now()
    
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

        fun findRecentByTenant(tenantId: UUID, limit: Int = 100): List<AlertHistory> =
            getEntityManager()
                .createQuery(
                    "SELECT a FROM AlertHistory a WHERE a.tenant.id = :tenantId ORDER BY a.sentAt DESC",
                    AlertHistory::class.java
                )
                .setParameter("tenantId", tenantId)
                .setMaxResults(limit)
                .resultList
    }
}
