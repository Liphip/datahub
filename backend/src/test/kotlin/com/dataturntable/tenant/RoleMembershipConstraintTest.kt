package com.dataturntable.tenant

import com.dataturntable.auth.User
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for `roles(tenant_id, name)` and `memberships(auth_user_id, tenant_id)`,
 * two of the 13 entities found missing their migration-declared `UNIQUE` constraint on `@Table`
 * (see `alert.EntityUniqueConstraintTest` for the full story).
 */
@QuarkusTest
class RoleMembershipConstraintTest {

    @Inject lateinit var em: EntityManager

    private fun newTenant(): Tenant = Tenant().apply { name = "Role Test ${UUID.randomUUID()}" }

    private fun newUser(): User = User().apply {
        email = "user-${UUID.randomUUID()}@example.com"
        passwordHash = "hash"
    }

    @Test
    fun `roles rejects a second role with the same tenant and name`() {
        val name = "Duplicate Role ${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                em.persist(Role().apply { this.tenant = tenant; this.name = name; abilities = "[]" })
                em.persist(Role().apply { this.tenant = tenant; this.name = name; abilities = "[]" })
                em.flush()
            }
        }
    }

    @Test
    fun `roles allows the same name across different tenants`() {
        val name = "Shared Role Name ${UUID.randomUUID()}"
        QuarkusTransaction.requiringNew().run {
            val tenantA = newTenant()
            val tenantB = newTenant()
            em.persist(tenantA)
            em.persist(tenantB)
            em.persist(Role().apply { tenant = tenantA; this.name = name; abilities = "[]" })
            em.persist(Role().apply { tenant = tenantB; this.name = name; abilities = "[]" })
            em.flush()
        }
    }

    @Test
    fun `memberships rejects a second membership for the same user and tenant`() {
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                val user = newUser()
                em.persist(tenant)
                em.persist(user)
                em.persist(Membership().apply { authUser = user; this.tenant = tenant })
                em.persist(Membership().apply { authUser = user; this.tenant = tenant })
                em.flush()
            }
        }
    }
}
