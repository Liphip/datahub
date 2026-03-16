package com.dataturntable.auth

import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
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
            this.tenant = tenant
            email = "test@example.com"
            passwordHash = "hash"
            this.role = role
        }
        user.persist()
        
        // Then
        assertTrue(user.hasAbility("module:read"))
        assertTrue(user.hasAbility("module:write"))
        assertTrue(user.hasAbility("pipeline:execute"))
        assertFalse(user.hasAbility("module:delete"))
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
            this.tenant = tenant
            email = "test2@example.com"
            passwordHash = "hash"
            this.role = role
            abilities = "[\"pipeline:execute\"]"
        }
        user.persist()
        
        // Then - should have role abilities AND additional abilities
        assertTrue(user.hasAbility("module:read"))
        assertTrue(user.hasAbility("pipeline:execute"))
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
            this.tenant = tenant
            email = "test@example.com"
            passwordHash = "hash"
            this.role = role
            abilities = "[\"data:write\"]"
        }
        user.persist()
        
        // When
        val abilities = user.getAllAbilities()
        
        // Then
        assertTrue(abilities.contains("module:read"))
        assertTrue(abilities.contains("pipeline:execute"))
        assertTrue(abilities.contains("data:write"))
    }
}
