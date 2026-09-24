package com.dataturntable.auth

import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.TestTransaction
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class UserTest {

    private fun tenant(parent: Tenant? = null): Tenant =
        Tenant().apply {
            name = "Test Tenant"
            this.parent = parent
        }.also { it.persist() }

    private fun role(tenant: Tenant, name: String, abilities: String): Role =
        Role().apply {
            this.tenant = tenant
            this.name = name
            this.abilities = abilities
        }.also { it.persist() }

    private fun user(email: String, abilities: String? = null): User =
        User().apply {
            this.email = email
            passwordHash = "hash"
            this.abilities = abilities
        }.also { it.persist() }

    /** Persists a membership and keeps both sides of the User <-> Membership relation in sync. */
    private fun addMembership(user: User, tenant: Tenant, role: Role) {
        val membership = Membership().apply {
            authUser = user
            this.tenant = tenant
            this.role = role
        }
        membership.persist()
        user.memberships.add(membership)
    }

    @Test
    @TestTransaction
    fun testUserHasAbility() {
        val tenant = tenant()
        val user = user("test@example.com")
        addMembership(user, tenant, role(tenant, "Editor", "[\"module:read\", \"module:write\", \"pipeline:execute\"]"))

        val abilities = user.getAllAbilities()
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("module:write"))
        assertTrue(abilities.contains("pipeline:execute"))
        assertFalse(abilities.contains("module:delete"))
    }

    @Test
    @TestTransaction
    fun testRootTenantAdminIsSuperadminWithAllAbilities() {
        val root = tenant()
        val user = user("admin@example.com")
        addMembership(user, root, role(root, "Tenant Admin", "[\"*\"]"))

        assertTrue(user.isSuperAdmin())
        assertTrue(user.hasAbility("module:read"))
        assertTrue(user.hasAbility("module:write"))
        assertTrue(user.hasAbility("module:delete"))
        assertTrue(user.hasAbility("tenant:manage"))
    }

    @Test
    @TestTransaction
    fun testChildTenantAdminIsNotSuperadmin() {
        val child = tenant(parent = tenant())
        val user = user("child-admin@example.com")
        addMembership(user, child, role(child, "Tenant Admin", "[\"*\"]"))

        assertFalse(user.isSuperAdmin())
    }

    @Test
    @TestTransaction
    fun testUserWithAdditionalAbilities() {
        val tenant = tenant()
        val user = user("test2@example.com", abilities = "[\"pipeline:execute\"]")
        addMembership(user, tenant, role(tenant, "Viewer", "[\"module:read\"]"))

        val abilities = user.getAllAbilities()
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("pipeline:execute"))
        assertFalse(abilities.contains("data:write"))
    }

    @Test
    @TestTransaction
    fun testSuperadminCanAccessAnyTenant() {
        val root = tenant()
        val user = user("root-admin@example.com")
        addMembership(user, root, role(root, "Tenant Admin", "[\"*\"]"))

        assertTrue(user.canAccessTenant(java.util.UUID.randomUUID()))
    }

    @Test
    @TestTransaction
    fun testGetAllAbilities() {
        val tenant = tenant()
        val user = user("test3@example.com", abilities = "[\"data:write\"]")
        addMembership(user, tenant, role(tenant, "Editor", "[\"module:read\", \"pipeline:execute\"]"))

        val abilities = user.getAllAbilities()
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("pipeline:execute"))
        assertTrue(abilities.contains("data:write"))
    }
}
