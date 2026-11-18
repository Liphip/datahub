package com.dataturntable.auth

import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MembershipValidityTest {
    @Test fun intervalIsStartInclusiveEndExclusive() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = start.plusSeconds(60)
        val membership = Membership().apply { validFrom = start; validUntil = end }
        assertFalse(membership.isEffective(start.minusNanos(1)))
        assertTrue(membership.isEffective(start))
        assertTrue(membership.isEffective(end.minusNanos(1)))
        assertFalse(membership.isEffective(end))
    }

    @Test fun futureAndExpiredMembershipsGrantNeitherTenantAccessNorAbilities() {
        val tenant = Tenant().apply { id = UUID.randomUUID() }
        val role = Role().apply { abilities = "[\"pipeline:read\"]" }
        val member = Membership().apply { this.tenant = tenant; this.role = role; abilities = "[\"module:read\"]" }
        val user = User().apply { memberships.add(member) }
        member.validFrom = Instant.now().plusSeconds(3600)
        assertFalse(user.canAccessTenant(tenant.id!!)); assertFalse(user.hasAbility("pipeline:read"))
        member.validFrom = null
        member.validUntil = Instant.now().minusSeconds(1)
        assertFalse(user.canAccessTenant(tenant.id!!)); assertTrue(user.getAllAbilities().isEmpty())
        assertNull(user.getPrimaryTenant()); assertNull(user.getMembershipForTenant(tenant.id!!))
        assertEquals(1, user.loadMemberships().size, "Administrative history must remain available")
        member.validUntil = Instant.now().plusSeconds(3600)
        assertTrue(user.canAccessTenant(tenant.id!!)); assertTrue(user.hasAbility("pipeline:read"))
        assertTrue(user.hasAbility("module:read"))
    }
}
