package com.dataturntable.alert

import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class AlertChannelType {
    MQTT, WEBHOOK, REST, EMAIL, SLACK
}

@Entity
@Table(name = "alert_channels")
open class AlertChannel : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @Column(nullable = false)
    var name: String? = null
    
    @Column
    var description: String? = null
    
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    var channelType: AlertChannelType? = null
    
    @Column(columnDefinition = "text", nullable = false)
    var config: String? = null
    
    @Column(name = "is_active")
    var isActive: Boolean = true
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null
    
    companion object : PanacheCompanionBase<AlertChannel, UUID> {
        override fun findById(id: UUID): AlertChannel? =
            find("id = ?1", id).firstResult()

        fun findByTenantId(tenantId: UUID): List<AlertChannel> =
            list("tenant.id = ?1", tenantId)

    }
}
