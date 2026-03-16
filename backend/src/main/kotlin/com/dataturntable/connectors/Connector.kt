package com.dataturntable.connectors

import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "connectors")
class Connector : PanacheEntityBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: com.dataturntable.tenant.Tenant? = null
    
    @Column(nullable = false)
    var name: String? = null
    
    @Column(name = "connector_type", nullable = false)
    @Enumerated(EnumType.STRING)
    var connectorType: ConnectorType? = null
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var direction: Direction? = null
    
    @Column(nullable = false, columnDefinition = "TEXT")
    var config: String = "{}"
    
    @Column(length = 500)
    var description: String? = null
    
    @Column(name = "is_active")
    var isActive: Boolean = true
    
    @Column(name = "created_at")
    var createdAt: Instant? = null
    
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: com.dataturntable.auth.User? = null
    
    companion object {
        fun findByTenantId(tenantId: UUID): List<Connector> {
            return PanacheEntityBase.list<Connector>("tenant.id = ?1", tenantId)
        }
        
        fun findByTenantAndType(tenantId: UUID, type: ConnectorType): List<Connector> {
            return PanacheEntityBase.list<Connector>("tenant.id = ?1 and connectorType = ?2", tenantId, type)
        }
        
        fun findByTenantAndDirection(tenantId: UUID, direction: Direction): List<Connector> {
            return PanacheEntityBase.list<Connector>("tenant.id = ?1 and direction = ?2", tenantId, direction)
        }
        
        fun findById(connectorId: UUID): Connector? {
            return PanacheEntityBase.find<Connector>("id", connectorId).firstResult()
        }
    }
    
    enum class ConnectorType {
        MQTT, REST, WEBSOCKET, UDP, FILE, CUSTOM
    }
    
    enum class Direction {
        INPUT, OUTPUT, BOTH
    }
}
