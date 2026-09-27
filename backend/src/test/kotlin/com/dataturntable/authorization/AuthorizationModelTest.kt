package com.dataturntable.authorization

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthorizationModelTest {
    @Test
    fun `root tenant administrator is not implicitly a platform administrator`() {
        val rootTenant = Tenant()
        val tenantAdmin = Role().apply {
            name = "Tenant Admin"
            abilities = "[\"manage:tenant\"]"
        }
        val user = User().apply {
            memberships += Membership().apply {
                tenant = rootTenant
                role = tenantAdmin
            }
        }

        assertFalse(user.isSuperAdmin())
        assertTrue(user.hasAbility("manage:tenant"))
    }

    @Test
    fun `relational grants participate in effective authorization`() {
        val ability = Ability().apply { value = "module:read" }
        val user = User().apply { grantedAbilities += ability }

        assertTrue(user.hasAbility("module:read"))
        assertFalse(user.hasAbility("read"))
    }
}
