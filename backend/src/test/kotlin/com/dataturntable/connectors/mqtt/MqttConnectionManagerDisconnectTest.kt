package com.dataturntable.connectors.mqtt

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.connectors.ConnectorLeaseService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.UUID

/**
 * `disconnectConnector` is a fast path `ConnectorResource`'s create/update/delete callbacks call
 * directly, from whichever replica the HTTP request happened to land on. In an HA deployment with
 * API and ingest roles split across replica pools, that need not be the replica holding this
 * connector's lease — releasing it from a replica that never owned it would let another ingest
 * replica acquire a fresh fencing token while the true owner's client is still live and unaware
 * its own token is now stale. Plain instantiation (no @QuarkusTest/CDI needed): both dependencies
 * exercised here have simple, direct constructors.
 */
class MqttConnectionManagerDisconnectTest {

    private fun manager(role: String): Pair<MqttConnectionManager, ConnectorLeaseService> {
        val leaseService = mock(ConnectorLeaseService::class.java)
        val manager = MqttConnectionManager().apply {
            runtimeRoleConfig = RuntimeRoleConfig(role, true)
            connectorLeaseService = leaseService
        }
        return manager to leaseService
    }

    @Test
    fun `a control-api-only replica does not release a lease it never owned`() {
        val (manager, leaseService) = manager("control-api")
        val connectorId = UUID.randomUUID()

        manager.disconnectConnector(connectorId)

        verify(leaseService, never()).release(connectorId)
    }

    @Test
    fun `an ingest replica still releases the lease on the fast path`() {
        val (manager, leaseService) = manager("ingest-edge")
        val connectorId = UUID.randomUUID()

        manager.disconnectConnector(connectorId)

        verify(leaseService).release(connectorId)
    }

    @Test
    fun `releaseLease = false never releases, regardless of role`() {
        val (manager, leaseService) = manager("ingest-edge")
        val connectorId = UUID.randomUUID()

        manager.disconnectConnector(connectorId, releaseLease = false)

        verify(leaseService, never()).release(connectorId)
    }

    @Test
    fun `the unqualified ALL role (single-node deployment) still releases`() {
        val (manager, leaseService) = manager("all")
        val connectorId = UUID.randomUUID()

        manager.disconnectConnector(connectorId)

        verify(leaseService).release(connectorId)
    }
}
