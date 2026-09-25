package com.dataturntable.connectors

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ApplicationScoped
/**
 * Defines the class `ConnectorLeaseService`, the contract implemented by this source unit.
 */
class ConnectorLeaseService {

    private val log = Logger.getLogger(ConnectorLeaseService::class.java)

    @PersistenceContext
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.runtime.role", defaultValue = "control-api")
    lateinit var runtimeRole: String

    @ConfigProperty(name = "dataturntable.runtime.instance-id")
    lateinit var configuredInstanceId: Optional<String>

    @ConfigProperty(name = "dataturntable.connectors.lease.ttl-seconds", defaultValue = "30")
    var leaseTtlSeconds: Long = 45

    private val instanceId: String by lazy {
        configuredInstanceId.orElseGet { "ingest-${UUID.randomUUID()}" }
    }

    /**
     * Implements `currentInstanceId` for this source unit.
     */
    fun currentInstanceId(): String = instanceId

    @Transactional
    /**
     * Implements `tryAcquireOrRenew` for this source unit.
     */
    fun tryAcquireOrRenew(connectorId: UUID): Boolean {
        val now = Instant.now()
        val leaseUntil = now.plusSeconds(leaseTtlSeconds.coerceAtLeast(10))
        val updated = em.createNativeQuery(
            """
            INSERT INTO connector_runtime_leases (connector_id, runtime_role, owner_instance_id, lease_until, updated_at)
            VALUES (:connectorId, :runtimeRole, :ownerInstanceId, :leaseUntil, :updatedAt)
            ON CONFLICT (connector_id) DO UPDATE
            SET runtime_role = EXCLUDED.runtime_role,
                owner_instance_id = EXCLUDED.owner_instance_id,
                lease_until = EXCLUDED.lease_until,
                updated_at = EXCLUDED.updated_at
            WHERE connector_runtime_leases.owner_instance_id = :ownerInstanceId
               OR connector_runtime_leases.lease_until <= :now
            """.trimIndent()
        )
            .setParameter("connectorId", connectorId)
            .setParameter("runtimeRole", runtimeRole.uppercase())
            .setParameter("ownerInstanceId", instanceId)
            .setParameter("leaseUntil", Timestamp.from(leaseUntil))
            .setParameter("updatedAt", Timestamp.from(now))
            .setParameter("now", Timestamp.from(now))
            .executeUpdate()
        return updated > 0
    }

    @Transactional
    /**
     * Implements `release` for this source unit.
     */
    fun release(connectorId: UUID) {
        try {
            em.createNativeQuery(
                "DELETE FROM connector_runtime_leases WHERE connector_id = :connectorId AND owner_instance_id = :ownerInstanceId"
            )
                .setParameter("connectorId", connectorId)
                .setParameter("ownerInstanceId", instanceId)
                .executeUpdate()
        } catch (_: Exception) {
            // Test schemas and partially initialized runtimes may not have the lease table yet.
        }
    }

    @Transactional
    /**
     * Implements `releaseAllOwned` for this source unit.
     */
    fun releaseAllOwned() {
        try {
            val count = em.createNativeQuery(
                "DELETE FROM connector_runtime_leases WHERE owner_instance_id = :ownerInstanceId"
            )
                .setParameter("ownerInstanceId", instanceId)
                .executeUpdate()
            if (count > 0) {
                log.infof("Released %d connector lease(s) for instance %s", count, instanceId)
            }
        } catch (_: Exception) {
            // Safe to ignore during shutdown when the lease table is unavailable.
        }
    }
}
