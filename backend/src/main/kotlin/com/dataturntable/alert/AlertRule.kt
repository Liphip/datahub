package com.dataturntable.alert

import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "alert_rules")
/**
 * Defines the class `AlertRule`, the contract implemented by this source unit.
 */
open class AlertRule : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id")
    var pipeline: Pipeline? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    var channel: AlertChannel? = null
    
    @Column(nullable = false)
    var name: String? = null
    
    @Column
    var description: String? = null
    
    @Column(columnDefinition = "text", nullable = false)
    var condition: String? = null
    
    @Column
    var enabled: Boolean = true
    
    @Column(name = "cooldown_seconds")
    var cooldownSeconds: Int = 60
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null
    
    @OneToMany(mappedBy = "rule", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @OrderBy("sentAt DESC")
    var history: MutableList<AlertHistory> = mutableListOf()
    
    companion object : PanacheCompanionBase<AlertRule, UUID> {
        override fun findById(id: UUID): AlertRule? =
            find("id = ?1", id).firstResult()

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantId(tenantId: UUID): List<AlertRule> =
            list("tenant.id = ?1", tenantId)

    }
}
