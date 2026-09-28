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

    data class LeaseClaim(val connectorId: UUID, val ownerInstanceId: String, val fencingToken: Long)

    private val log = Logger.getLogger(ConnectorLeaseService::class.java)

    @PersistenceContext
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.runtime.role", defaultValue = "control-api")
    lateinit var runtimeRole: String

    @ConfigProperty(name = "dataturntable.runtime.instance-id")
    lateinit var configuredInstanceId: Optional<String>

    @ConfigProperty(name = "dataturntable.connectors.lease.ttl-seconds", defaultValue = "30")
    var leaseTtlSeconds: Long = 45

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    lateinit var dbKind: String

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
        return acquireOrRenew(connectorId) != null
    }

    /** Returns the current fencing generation only when this runtime owns the lease. */
    @Transactional
    fun acquireOrRenew(connectorId: UUID): LeaseClaim? {
        val now = Instant.now()
        val leaseUntil = now.plusSeconds(leaseTtlSeconds.coerceAtLeast(10))
        if (dbKind == "h2") {
            // H2 test schema lacks PostgreSQL's ON CONFLICT ... DO UPDATE ... WHERE upsert form;
            // production always uses the atomic path below.
            return tryAcquireOrRenewH2Fallback(connectorId, now, leaseUntil)
        }
        val updated = em.createNativeQuery(
            """
            INSERT INTO connector_runtime_leases (connector_id, runtime_role, owner_instance_id, lease_until, fencing_token, updated_at)
            VALUES (:connectorId, :runtimeRole, :ownerInstanceId,
                    CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'), 1, CURRENT_TIMESTAMP)
            ON CONFLICT (connector_id) DO UPDATE
            SET runtime_role = EXCLUDED.runtime_role,
                owner_instance_id = EXCLUDED.owner_instance_id,
                lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
                fencing_token = CASE
                    WHEN connector_runtime_leases.owner_instance_id = EXCLUDED.owner_instance_id
                    THEN connector_runtime_leases.fencing_token
                    ELSE connector_runtime_leases.fencing_token + 1
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE connector_runtime_leases.owner_instance_id = :ownerInstanceId
               OR connector_runtime_leases.lease_until <= CURRENT_TIMESTAMP
            """.trimIndent()
        )
            .setParameter("connectorId", connectorId)
            .setParameter("runtimeRole", runtimeRole.uppercase())
            .setParameter("ownerInstanceId", instanceId)
            .setParameter("leaseSeconds", leaseTtlSeconds.coerceAtLeast(10))
            .executeUpdate()
        if (updated == 0) return null
        val lease = em.find(ConnectorRuntimeLease::class.java, connectorId) ?: return null
        return LeaseClaim(connectorId, instanceId, lease.fencingToken)
    }

    private fun tryAcquireOrRenewH2Fallback(connectorId: UUID, now: Instant, leaseUntil: Instant): LeaseClaim? {
        val existing = em.find(ConnectorRuntimeLease::class.java, connectorId)
        if (existing != null && existing.ownerInstanceId != instanceId && existing.leaseUntil.isAfter(now)) {
            return null
        }
        if (existing != null) {
            if (existing.ownerInstanceId != instanceId) existing.fencingToken += 1
            existing.runtimeRole = runtimeRole.uppercase()
            existing.ownerInstanceId = instanceId
            existing.leaseUntil = leaseUntil
            existing.updatedAt = now
        } else {
            em.persist(ConnectorRuntimeLease().apply {
                this.connectorId = connectorId
                this.runtimeRole = runtimeRole.uppercase()
                this.ownerInstanceId = instanceId
                this.leaseUntil = leaseUntil
                this.fencingToken = 1
                this.updatedAt = now
            })
        }
        return LeaseClaim(connectorId, instanceId, existing?.fencingToken ?: 1)
    }

    /** A stale runtime must not write authoritative connector state after failover. */
    @Transactional
    fun owns(connectorId: UUID, fencingToken: Long): Boolean {
        val lease = em.find(ConnectorRuntimeLease::class.java, connectorId) ?: return false
        return lease.ownerInstanceId == instanceId && lease.fencingToken == fencingToken && lease.leaseUntil.isAfter(Instant.now())
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
