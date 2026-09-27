package com.dataturntable.connectors

import com.dataturntable.test.PostgresTestProfile
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ConnectorLeaseService` is normally CDI-injected, relying on `@Transactional` interception to
 * wrap each call in a JTA transaction. This test constructs it manually (deliberately — each
 * racing thread needs its own real database connection, not one shared CDI-managed
 * EntityManager/transaction), which bypasses that interception entirely. Run the same body inside
 * an explicit resource-local `EntityTransaction` instead to get equivalent atomicity.
 */
private fun <T> ConnectorLeaseService.inOwnTransaction(body: (ConnectorLeaseService) -> T): T {
    val tx = em.transaction
    tx.begin()
    try {
        val result = body(this)
        tx.commit()
        return result
    } catch (e: Exception) {
        if (tx.isActive) tx.rollback()
        throw e
    }
}

/**
 * FR-018's acceptance criterion is "two replicas produce one valid owner" and "a stale owner
 * cannot overwrite current authoritative state." `ConnectorLeaseServiceTest` already proves this
 * at the unit level (two `ConnectorLeaseService` instances sharing one EntityManager/transaction),
 * but that never exercises genuinely concurrent database transactions — the actual mechanism this
 * guarantee depends on is Postgres's real row-level locking during the atomic
 * `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE` upsert (see `ConnectorLeaseService.acquireOrRenew`).
 * This test drives that real path: N threads, each with its own `EntityManager` (its own real
 * database connection/transaction, not a shared one) racing to acquire the same connector lease
 * against a real PostgreSQL instance, synchronized to start at the same instant via a barrier to
 * make the race as tight as possible.
 */
@QuarkusTest
@TestProfile(PostgresTestProfile::class)
class ConnectorLeaseConcurrencyPostgresIT {
    @Inject
    lateinit var emf: EntityManagerFactory

    /** connector_runtime_leases.connector_id has a real FK to connectors(id) on Postgres (unlike the H2 test schema), so a real Connector row must exist first. */
    private fun persistRealConnector(): UUID {
        val em = emf.createEntityManager()
        try {
            em.transaction.begin()
            val tenant = Tenant().apply { name = "lease-drill-${UUID.randomUUID()}" }
            em.persist(tenant)
            val connector = Connector().apply {
                this.tenant = tenant
                name = "lease-drill-connector-${UUID.randomUUID()}"
                connectorType = Connector.ConnectorType.MQTT
                direction = Connector.Direction.INPUT
            }
            em.persist(connector)
            em.transaction.commit()
            return connector.id!!
        } finally {
            em.close()
        }
    }

    private fun leaseServiceForNewConnection(instanceId: String): ConnectorLeaseService {
        val service = ConnectorLeaseService()
        service.em = emf.createEntityManager()
        service.runtimeRole = "ingest-edge"
        service.configuredInstanceId = Optional.of(instanceId)
        service.leaseTtlSeconds = 10
        service.dbKind = "postgresql"
        return service
    }

    @Test
    fun `N genuinely concurrent runtime instances racing acquireOrRenew produce exactly one winner`() {
        val connectorId = persistRealConnector()
        val instanceCount = 8
        val instanceIds = (1..instanceCount).map { "racer-$it-${UUID.randomUUID()}" }
        val barrier = CyclicBarrier(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)
        val winners = AtomicInteger(0)
        val results = java.util.concurrent.ConcurrentHashMap<String, Optional<ConnectorLeaseService.LeaseClaim>>()

        try {
            val futures = instanceIds.map { instanceId ->
                executor.submit {
                    val service = leaseServiceForNewConnection(instanceId)
                    try {
                        barrier.await(30, TimeUnit.SECONDS)
                        val claim = service.inOwnTransaction { it.acquireOrRenew(connectorId) }
                        results[instanceId] = Optional.ofNullable(claim)
                        if (claim != null) winners.incrementAndGet()
                    } finally {
                        service.em.close()
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(1, winners.get(), "exactly one of $instanceCount genuinely concurrent racers must win the lease; got: $results")

        val winnerId = results.entries.single { it.value.isPresent }.key
        val verifyEm = emf.createEntityManager()
        try {
            val lease = verifyEm.find(ConnectorRuntimeLease::class.java, connectorId)
            assertEquals(winnerId, lease.ownerInstanceId, "the persisted lease owner must match the thread that won acquireOrRenew")
            assertEquals(1L, lease.fencingToken, "the first-ever acquisition must start at fencing token 1")
        } finally {
            verifyEm.close()
        }
    }

    @Test
    fun `a stale owner is fenced out after real expiry and a concurrent takeover still produces exactly one new owner`() {
        val connectorId = persistRealConnector()
        val original = leaseServiceForNewConnection("original-owner-${UUID.randomUUID()}")
        val firstClaim: ConnectorLeaseService.LeaseClaim?
        try {
            firstClaim = original.inOwnTransaction { it.acquireOrRenew(connectorId) }
        } finally {
            original.em.close()
        }
        assertTrue(firstClaim != null, "initial acquisition must succeed")

        // Force real expiry via a genuinely separate connection/transaction, not the original's.
        val expireEm = emf.createEntityManager()
        try {
            expireEm.transaction.begin()
            val lease = expireEm.find(ConnectorRuntimeLease::class.java, connectorId)
            lease.leaseUntil = Instant.now().minusSeconds(5)
            expireEm.transaction.commit()
        } finally {
            expireEm.close()
        }

        val challengerCount = 5
        val challengerIds = (1..challengerCount).map { "challenger-$it-${UUID.randomUUID()}" }
        val barrier = CyclicBarrier(challengerCount)
        val executor = Executors.newFixedThreadPool(challengerCount)
        val results = java.util.concurrent.ConcurrentHashMap<String, Optional<ConnectorLeaseService.LeaseClaim>>()

        try {
            val futures = challengerIds.map { instanceId ->
                executor.submit {
                    val service = leaseServiceForNewConnection(instanceId)
                    try {
                        barrier.await(30, TimeUnit.SECONDS)
                        results[instanceId] = Optional.ofNullable(service.inOwnTransaction { it.acquireOrRenew(connectorId) })
                    } finally {
                        service.em.close()
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        val winners = results.count { it.value.isPresent }
        assertEquals(1, winners, "exactly one challenger must win the takeover after real expiry; got: $results")

        val newOwnerId = results.entries.single { it.value.isPresent }.key
        val newOwnerClaim = results[newOwnerId]!!.get()
        assertEquals(firstClaim!!.fencingToken + 1, newOwnerClaim.fencingToken, "fencing token must strictly increase on takeover")

        // The stale original owner must not be able to prove ownership at the old fencing token.
        val staleCheck = leaseServiceForNewConnection(firstClaim.ownerInstanceId)
        try {
            val stillOwns = staleCheck.inOwnTransaction { it.owns(connectorId, firstClaim.fencingToken) }
            assertTrue(!stillOwns, "the original owner must not still validate as owning the lease after a real takeover")
        } finally {
            staleCheck.em.close()
        }
    }
}
