package com.dataturntable.connectors

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

@QuarkusTest
class ConnectorLeaseServiceTest {
    @Inject lateinit var leases: ConnectorLeaseService
    @Inject lateinit var em: EntityManager

    @Test
    @Transactional
    fun `expired foreign lease is reacquired with a higher fencing token`() {
        val connectorId = UUID.randomUUID()
        val first = leases.acquireOrRenew(connectorId)
        assertNotNull(first)
        assertTrue(leases.owns(connectorId, first!!.fencingToken))

        val stored = em.find(ConnectorRuntimeLease::class.java, connectorId)
        stored.ownerInstanceId = "failed-runtime"
        stored.leaseUntil = Instant.now().minusSeconds(1)
        em.flush()

        val second = leases.acquireOrRenew(connectorId)
        assertNotNull(second)
        assertEquals(first.fencingToken + 1, second!!.fencingToken)
        assertTrue(leases.owns(connectorId, second.fencingToken))
        assertFalse(leases.owns(connectorId, first.fencingToken))
    }

    @Test
    @Transactional
    fun `two runtime instances have one owner until expiry and stale generation is rejected`() {
        val connectorId = UUID.randomUUID()
        val primary = runtime("primary")
        val standby = runtime("standby")

        val first = primary.acquireOrRenew(connectorId)
        assertNotNull(first)
        assertEquals(null, standby.acquireOrRenew(connectorId))

        val stored = em.find(ConnectorRuntimeLease::class.java, connectorId)
        stored.leaseUntil = Instant.now().minusSeconds(1)
        em.flush()

        val takeover = standby.acquireOrRenew(connectorId)
        assertNotNull(takeover)
        assertEquals(first!!.fencingToken + 1, takeover!!.fencingToken)
        assertTrue(standby.owns(connectorId, takeover.fencingToken))
        assertFalse(primary.owns(connectorId, first.fencingToken))
    }

    private fun runtime(instanceId: String) = ConnectorLeaseService().apply {
        this.em = this@ConnectorLeaseServiceTest.em
        runtimeRole = "ingest-edge"
        configuredInstanceId = Optional.of(instanceId)
        leaseTtlSeconds = 10
        dbKind = "h2"
    }
}
