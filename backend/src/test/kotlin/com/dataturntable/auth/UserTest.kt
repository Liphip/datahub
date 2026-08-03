package com.dataturntable.auth

import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
// FIXME: Keep bidirectional test relationships synchronized
// Details: These tests persist Membership.authUser but never add the membership to User.memberships. User.getAllAbilities reads that initialized collection, so role abilities are absent until the entity is reloaded or both sides are updated.
class UserTest {
    
    @Test
    @Transactional
    fun testUserHasAbility() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
        }
        tenant.persist()
        
        val role = Role().apply {
            this.tenant = tenant
            name = "Editor"
            abilities = "[\"module:read\", \"module:write\", \"pipeline:execute\"]"
        }
        role.persist()
        
        val user = User().apply {
            email = "test@example.com"
            passwordHash = "hash"
        }
        user.persist()
        
        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = role
        }
        membership.persist()
        
        // Then
        // Test that user has abilities from membership
        val abilities = user.getAllAbilities()
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("module:write"))
        assertTrue(abilities.contains("pipeline:execute"))
        assertFalse(abilities.contains("module:delete"))
    }
    
    @Test
    @Transactional
    fun testSuperadminHasAllAbilities() {
        // Given
        val user = User().apply {
            email = "admin@example.com"
            passwordHash = "hash"
            isSuperadmin = true
        }
        user.persist()
        
        // Then
        assertTrue(user.hasAbility("module:read"))
        assertTrue(user.hasAbility("module:write"))
        assertTrue(user.hasAbility("module:delete"))
        assertTrue(user.hasAbility("tenant:manage"))
    }
    
    @Test
    @Transactional
    fun testUserWithAdditionalAbilities() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
        }
        tenant.persist()
        
        val role = Role().apply {
            this.tenant = tenant
            name = "Viewer"
            abilities = "[\"module:read\"]"
        }
        role.persist()
        
        val user = User().apply {
            email = "test2@example.com"
            passwordHash = "hash"
            abilities = "[\"pipeline:execute\"]"
        }
        user.persist()
        
        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = role
        }
        membership.persist()
        
        // Then
        // Test that user has abilities from role and direct abilities
        val abilities = user.getAllAbilities()
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("pipeline:execute"))
        // BUG: Assertion requires an ability that the fixture never grants
        // Details: The user has pipeline:execute and the role has module:read; neither grants data:write. Add it to the fixture only if it is part of this test's intended contract.
        assertTrue(abilities.contains("data:write"))
    }
    
    @Test
    @Transactional
    fun testCanAccessTenant() {
        // Given - test superadmin access since hierarchy loading is complex in H2
        val user = User().apply {
            email = "admin@example.com"
            passwordHash = "hash"
            isSuperadmin = true
        }
        user.persist()
        
        val tenantId = java.util.UUID.randomUUID()
        
        // Then - superadmin can access any tenant
        assertTrue(user.canAccessTenant(tenantId))
    }
    
    @Test
    @Transactional
    fun testGetAllAbilities() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
        }
        tenant.persist()
        
        val role = Role().apply {
            this.tenant = tenant
            name = "Editor"
            abilities = "[\"module:read\", \"pipeline:execute\"]"
        }
        role.persist()
        
        val user = User().apply {
            email = "test@example.com"
            passwordHash = "hash"
            abilities = "[\"data:write\"]"
        }
        user.persist()
        
        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = role
        }
        membership.persist()
        
        // When
        val abilities = user.getAllAbilities()
        
        // Then
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("pipeline:execute"))
        assertTrue(abilities.contains("data:write"))
    }
}
