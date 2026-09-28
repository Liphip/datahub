package com.dataturntable.auth

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
 * Regression coverage for `api_keys(tenant_id, name)`, one of the 13 entities found missing its
 * migration-declared `UNIQUE` constraint on `@Table` (see `alert.EntityUniqueConstraintTest` for
 * the full story). Verifies the database enforces it directly, independent of any
 * application-level check.
 */
@QuarkusTest
class ApiKeyConstraintTest {

    @Inject lateinit var em: EntityManager

    private fun newTenant(): Tenant = Tenant().apply { name = "Api Key Test ${UUID.randomUUID()}" }

    @Test
    fun `api_keys rejects a second key with the same tenant and name`() {
        val name = "Duplicate Key ${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                em.persist(ApiKey().apply { this.tenant = tenant; this.name = name; keyHash = "hash1" })
                em.persist(ApiKey().apply { this.tenant = tenant; this.name = name; keyHash = "hash2" })
                em.flush()
            }
        }
    }

    @Test
    fun `api_keys allows the same name across different tenants`() {
        val name = "Shared Key Name ${UUID.randomUUID()}"
        QuarkusTransaction.requiringNew().run {
            val tenantA = newTenant()
            val tenantB = newTenant()
            em.persist(tenantA)
            em.persist(tenantB)
            em.persist(ApiKey().apply { tenant = tenantA; this.name = name; keyHash = "hash1" })
            em.persist(ApiKey().apply { tenant = tenantB; this.name = name; keyHash = "hash2" })
            em.flush()
        }
    }
}
