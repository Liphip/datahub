package com.dataturntable.connectors

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "connectors", uniqueConstraints = [jakarta.persistence.UniqueConstraint(columnNames = ["tenant_id", "name"])])
/**
 * Defines the class `Connector`, the contract implemented by this source unit.
 */
class Connector : PanacheBase() {
    
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

    /**
     * Whether the platform scheduler should poll this connector on an interval.
     * Only applicable for INPUT connectors (e.g. a REST API that DataTurntable fetches from periodically).
     * Push connectors (MQTT, WebSocket, webhooks) keep this false — they auto-trigger when data arrives.
     */
    @Column(name = "polling_enabled")
    var pollingEnabled: Boolean = false

    /**
     * Polling interval in seconds. Used when pollingEnabled = true.
     */
    @Column(name = "polling_interval_seconds")
    var pollingIntervalSeconds: Int = 60

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: com.dataturntable.auth.User? = null
    
    companion object : PanacheCompanionBase<Connector, UUID> {
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantId(tenantId: UUID): List<Connector> {
            return getEntityManager()
                .createQuery("SELECT c FROM Connector c WHERE c.tenant.id = :tenantId", Connector::class.java)
                .setParameter("tenantId", tenantId)
                .resultList
        }
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantAndType(tenantId: UUID, type: ConnectorType): List<Connector> {
            return getEntityManager()
                .createQuery(
                    "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.connectorType = :type",
                    Connector::class.java
                )
                .setParameter("tenantId", tenantId)
                .setParameter("type", type)
                .resultList
        }
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantAndDirection(tenantId: UUID, direction: Direction): List<Connector> {
            return getEntityManager()
                .createQuery(
                    "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.direction = :direction",
                    Connector::class.java
                )
                .setParameter("tenantId", tenantId)
                .setParameter("direction", direction)
                .resultList
        }
        
        override fun findById(connectorId: UUID): Connector? {
            return getEntityManager()
                .createQuery("SELECT c FROM Connector c WHERE c.id = :id", Connector::class.java)
                .setParameter("id", connectorId)
                .resultList
                .firstOrNull()
        }
    }
    
    /**
     * Defines the enum class `ConnectorType`, the contract implemented by this source unit.
     */
    enum class ConnectorType {
        MQTT, REST, WEBSOCKET, UDP, FILE, FTP, SFTP, EMAIL, CUSTOM
    }
    
    /**
     * Defines the enum class `Direction`, the contract implemented by this source unit.
     */
    enum class Direction {
        /** Connector receives data into the platform. Used on IMPORT nodes. */
        INPUT,
        /** Connector sends data out of the platform. Used on EXPORT nodes. */
        OUTPUT
    }
}
