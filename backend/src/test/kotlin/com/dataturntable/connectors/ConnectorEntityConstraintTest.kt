package com.dataturntable.connectors

import com.dataturntable.connectors.inbox.ConnectorInboxItem
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for the connector-related entities found missing their migration-declared
 * `UNIQUE` constraint on `@Table` (see `alert.EntityUniqueConstraintTest` for the full story):
 * `broker_credentials` (two constraints), `connector_pipeline_map`, `connector_revisions`, and
 * `connector_inbox_items` — the last one backs the pull-connector exactly-once ledger, so this is
 * the one entity in the sweep where the missing constraint was functionally significant, not just
 * defense in depth.
 */
@QuarkusTest
class ConnectorEntityConstraintTest {

    @Inject lateinit var em: EntityManager

    private fun newTenant(): Tenant = Tenant().apply { name = "Connector Entity Test ${UUID.randomUUID()}" }

    private fun newConnector(tenant: Tenant, name: String = "Connector ${UUID.randomUUID()}"): Connector =
        Connector().apply {
            this.tenant = tenant
            this.name = name
            connectorType = Connector.ConnectorType.FILE
            direction = Connector.Direction.INPUT
            config = "{}"
        }

    @Test
    fun `broker_credentials rejects a second credential with the same tenant and name`() {
        val name = "Duplicate Credential ${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                em.persist(BrokerCredential().apply {
                    this.tenant = tenant; this.name = name; username = "userA"
                    passwordEncrypted = "enc1"; createdAt = java.time.Instant.now(); updatedAt = java.time.Instant.now()
                })
                em.persist(BrokerCredential().apply {
                    this.tenant = tenant; this.name = name; username = "userB"
                    passwordEncrypted = "enc2"; createdAt = java.time.Instant.now(); updatedAt = java.time.Instant.now()
                })
                em.flush()
            }
        }
    }

    @Test
    fun `broker_credentials rejects a second credential with the same tenant and username`() {
        val username = "duplicate-user-${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                em.persist(BrokerCredential().apply {
                    this.tenant = tenant; name = "Credential A ${UUID.randomUUID()}"; this.username = username
                    passwordEncrypted = "enc1"; createdAt = java.time.Instant.now(); updatedAt = java.time.Instant.now()
                })
                em.persist(BrokerCredential().apply {
                    this.tenant = tenant; name = "Credential B ${UUID.randomUUID()}"; this.username = username
                    passwordEncrypted = "enc2"; createdAt = java.time.Instant.now(); updatedAt = java.time.Instant.now()
                })
                em.flush()
            }
        }
    }

    @Test
    fun `connector_pipeline_map rejects a second mapping with the same connector, pipeline and node`() {
        val nodeId = "node-${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val connector = newConnector(tenant)
                em.persist(connector)
                val pipeline = Pipeline().apply { this.tenant = tenant; name = "Pipeline ${UUID.randomUUID()}" }
                em.persist(pipeline)
                em.persist(ConnectorPipelineMap().apply {
                    connectorId = connector.id; pipelineId = pipeline.id; this.nodeId = nodeId; nodeType = "SOURCE"
                })
                em.persist(ConnectorPipelineMap().apply {
                    connectorId = connector.id; pipelineId = pipeline.id; this.nodeId = nodeId; nodeType = "SOURCE"
                })
                em.flush()
            }
        }
    }

    @Test
    fun `connector_revisions rejects a second row with the same connector and revision number`() {
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val connector = newConnector(tenant)
                em.persist(connector)
                em.persist(ConnectorRevision().apply { connectorId = connector.id; revision = 1; snapshot = "{}" })
                em.persist(ConnectorRevision().apply { connectorId = connector.id; revision = 1; snapshot = "{}" })
                em.flush()
            }
        }
    }

    @Test
    fun `connector_inbox_items rejects a second row with the same connector and item key`() {
        // This is the exactly-once pull-connector ledger (InboxPoller): a duplicate here would
        // mean the same source item could be queued twice after a crash-and-retry.
        val itemKey = "item-${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val connector = newConnector(tenant)
                em.persist(connector)
                em.persist(ConnectorInboxItem().apply { connectorId = connector.id; this.itemKey = itemKey })
                em.persist(ConnectorInboxItem().apply { connectorId = connector.id; this.itemKey = itemKey })
                em.flush()
            }
        }
    }
}
