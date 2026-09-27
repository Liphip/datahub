package com.dataturntable.security

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbilityEvaluatorTest {
    @Test
    fun `grants only an exact token or wildcard token`() {
        val serialized = "[\"module:read\", \"report:read\"]"

        assertTrue(AbilityEvaluator.hasAbility(serialized, "module:read"))
        assertFalse(AbilityEvaluator.hasAbility(serialized, "read"))
        assertFalse(AbilityEvaluator.hasAbility(serialized, "module"))
        assertFalse(AbilityEvaluator.hasAbility(serialized, "module:rea"))
        assertFalse(AbilityEvaluator.hasAbility(serialized, "module:read:all"))
    }

    @Test
    fun `does not interpret malformed serialized abilities as permissions`() {
        assertFalse(AbilityEvaluator.hasAbility("module:read", "module:read"))
        assertFalse(AbilityEvaluator.hasAbility("[\"module:read\"] trailing", "module:read"))
    }

    @Test
    fun `role authorization uses the canonical exact evaluator`() {
        val role = Role().apply { abilities = "[\"connector:read\"]" }

        assertTrue(role.hasAbility("connector:read"))
        assertFalse(role.hasAbility("read"))
        assertFalse(role.hasAbility("connector"))
    }

    @Test
    fun `user evaluates direct membership and role abilities as exact tokens`() {
        val user = User().apply { abilities = "[\"pipeline:execute\"]" }
        val role = Role().apply { abilities = "[\"module:read\"]" }
        user.memberships += Membership().apply {
            this.abilities = "[\"data:write\"]"
            this.role = role
        }

        assertTrue(user.hasAbility("pipeline:execute"))
        assertTrue(user.hasAbility("module:read"))
        assertTrue(user.hasAbility("data:write"))
        assertFalse(user.hasAbility("execute"))
        assertFalse(user.hasAbility("read"))
        assertFalse(user.hasAbility("write"))
    }
}
