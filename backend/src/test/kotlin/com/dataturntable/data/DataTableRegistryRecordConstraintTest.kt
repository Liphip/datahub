package com.dataturntable.data

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
 * Regression coverage for `data_table_registry(tenant_id, table_name)`, one of the 13 entities
 * found missing its migration-declared `UNIQUE` constraint on `@Table` (see
 * `alert.EntityUniqueConstraintTest` for the full story).
 */
@QuarkusTest
class DataTableRegistryRecordConstraintTest {

    @Inject lateinit var em: EntityManager

    @Test
    fun `data_table_registry rejects a second row with the same tenant and table name`() {
        val tableName = "duplicate_table_${UUID.randomUUID().toString().replace("-", "_")}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = Tenant().apply { name = "Registry Test ${UUID.randomUUID()}" }
                em.persist(tenant)
                em.persist(DataTableRegistryRecord().apply {
                    id = UUID.randomUUID(); tenantId = tenant.id; this.tableName = tableName; tableType = "METADATA"
                })
                em.persist(DataTableRegistryRecord().apply {
                    id = UUID.randomUUID(); tenantId = tenant.id; this.tableName = tableName; tableType = "METADATA"
                })
                em.flush()
            }
        }
    }
}
