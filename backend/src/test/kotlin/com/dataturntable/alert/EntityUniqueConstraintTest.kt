package com.dataturntable.alert

import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
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
 * Regression coverage for two `UNIQUE` constraints that exist in the baseline migration as
 * `CREATE UNIQUE INDEX` (not `ALTER TABLE ... ADD CONSTRAINT UNIQUE`) and were, like the 13
 * table-level ones fixed alongside `CodeModule` earlier, missing from their entity's `@Table`
 * annotation — so H2's schema-generated-from-entities test database never enforced them.
 */
@QuarkusTest
class EntityUniqueConstraintTest {

    @Inject lateinit var em: EntityManager

    private fun newTenant(): Tenant = Tenant().apply { name = "Constraint Test ${UUID.randomUUID()}" }

    @Test
    fun `alert_history rejects a second row with the same delivery key`() {
        val key = "delivery-${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                em.persist(AlertHistory().apply {
                    this.tenant = tenant
                    severity = AlertSeverity.INFO
                    title = "first"
                    deliveryKey = key
                })
                em.persist(AlertHistory().apply {
                    this.tenant = tenant
                    severity = AlertSeverity.INFO
                    title = "second"
                    deliveryKey = key
                })
                em.flush()
            }
        }
    }

    @Test
    fun `alert_history allows multiple rows with a null delivery key`() {
        // Standard SQL unique-constraint semantics (Postgres and H2 alike): NULL is never equal
        // to NULL, so a nullable unique column allows any number of NULLs. Only a non-null
        // duplicate must be rejected.
        QuarkusTransaction.requiringNew().run {
            val tenant = newTenant()
            em.persist(tenant)
            em.persist(AlertHistory().apply { this.tenant = tenant; severity = AlertSeverity.INFO; title = "a" })
            em.persist(AlertHistory().apply { this.tenant = tenant; severity = AlertSeverity.INFO; title = "b" })
            em.flush()
        }
    }

    @Test
    fun `module_versions rejects a second version with the same content fingerprint`() {
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val module = CodeModule().apply {
                    this.tenant = tenant
                    name = "Fingerprint Test ${UUID.randomUUID()}"
                    moduleType = ModuleType.PROCESSING
                }
                em.persist(module)
                em.persist(ModuleVersion().apply {
                    this.module = module
                    version = 1
                    code = "def process(input_data, query): return input_data"
                    contentFingerprint = "same-fingerprint"
                })
                em.persist(ModuleVersion().apply {
                    this.module = module
                    version = 2
                    code = "def process(input_data, query): return input_data"
                    contentFingerprint = "same-fingerprint"
                })
                em.flush()
            }
        }
    }
}
