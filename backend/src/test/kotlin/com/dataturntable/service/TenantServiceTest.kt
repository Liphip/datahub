package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.dto.CreateTenantRequest
import com.dataturntable.dto.UpdateTenantRequest
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

@QuarkusTest
class TenantServiceTest {
    
    @Inject
    lateinit var tenantService: TenantService
    
    @Test
    @Transactional
    fun testCreateTenant() {
        // Given
        val parentTenant = Tenant().apply {
            name = "Parent Tenant"
        }
        parentTenant.persist()
        
        val user = User().apply {
            email = "test@example.com"
        }
        
        val request = CreateTenantRequest(
            name = "Child Tenant",
            parentId = parentTenant.id.toString(),
            quota = mapOf("max_users" to 50)
        )
        
        // When
        val tenant = tenantService.createTenant(request, parentTenant.id, user)
        
        // Then
        assertNotNull(tenant)
        assertEquals("Child Tenant", tenant.name)
        assertNotNull(tenant.parent)
        assertEquals(parentTenant.id, tenant.parent?.id)
    }
    
    @Test
    @Transactional
    fun testGetTenant() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
            settings = """{"theme": "dark"}"""
        }
        tenant.persist()
        
        // When
        val found = tenantService.getTenant(tenant.id!!)
        
        // Then
        assertNotNull(found)
        assertEquals("Test Tenant", found?.name)
    }
    
    @Test
    @Transactional
    fun testUpdateTenant() {
        // Given
        val tenant = Tenant().apply {
            name = "Original Name"
        }
        tenant.persist()
        
        val request = UpdateTenantRequest(
            name = "Updated Name",
            settings = mapOf("theme" to "light")
        )
        
        // When
        val updated = tenantService.updateTenant(tenant.id!!, request)
        
        // Then
        assertNotNull(updated)
        assertEquals("Updated Name", updated?.name)
    }
    
    @Test
    @Transactional
    fun testDeleteTenant() {
        // Given
        val tenant = Tenant().apply {
            name = "To Delete"
        }
        tenant.persist()
        val id = tenant.id!!
        
        // When
        val deleted = tenantService.deleteTenant(id)
        
        // Then
        assertTrue(deleted)
        assertNull(tenantService.getTenant(id))
    }
    
    @Test
    @Transactional
    fun testTenantHierarchy() {
        // Given
        val root = Tenant().apply {
            name = "Root"
        }
        root.persist()
        
        val child1 = Tenant().apply {
            name = "Child 1"
            parent = root
        }
        child1.persist()
        
        val child2 = Tenant().apply {
            name = "Child 2"
            parent = root
        }
        child2.persist()
        
        // When
        val isDescendant = child1.isDescendantOf(root.id!!)
        val ancestorIds = child1.getAncestorIds()
        
        // Then
        assertTrue(isDescendant)
        assertTrue(ancestorIds.contains(root.id))
    }
}
