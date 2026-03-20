package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.*

@ApplicationScoped
class TenantService {
    
    @Inject
    lateinit var userService: UserService
    
    private val objectMapper = ObjectMapper()
    
    @Transactional
    fun createTenant(request: CreateTenantRequest, parentId: UUID?, user: User): Tenant {
        val parent = if (parentId != null) {
            Tenant.findById(parentId) ?: throw IllegalArgumentException("Parent tenant not found")
        } else {
            null
        }
        
        val tenant = Tenant().apply {
            name = request.name
            this.parent = parent
            this.settings = objectMapper.writeValueAsString(request.settings)
            this.quota = objectMapper.writeValueAsString(request.quota)
        }
        tenant.persist()
        
        // Create default roles for new tenant
        createDefaultRoles(tenant)
        
        return tenant
    }
    
    fun createDefaultRoles(tenant: Tenant) {
        val viewerRole = Role().apply {
            this.tenant = tenant
            name = "Viewer"
            description = "Default viewer role"
            abilities = "[\"module:read\", \"pipeline:read\", \"data:read\", \"alert:read\", \"read:connector\", \"read:user\"]"
            isDefault = true
        }
        viewerRole.persist()
        
        val editorRole = Role().apply {
            this.tenant = tenant
            name = "Editor"
            description = "Default editor role"
            abilities = "[\"module:read\", \"module:write\", \"pipeline:read\", \"pipeline:write\", \"pipeline:execute\", \"data:read\", \"data:write\", \"alert:read\", \"read:connector\", \"write:connector\", \"read:user\"]"
        }
        editorRole.persist()
        
        val adminRole = Role().apply {
            this.tenant = tenant
            name = "Tenant Admin"
            description = "Tenant administrator role"
            abilities = "[\"module:read\", \"module:write\", \"module:delete\", \"pipeline:read\", \"pipeline:write\", \"pipeline:execute\", \"data:read\", \"data:write\", \"data:export\", \"alert:read\", \"alert:configure\", \"tenant:users\", \"tenant:manage\", \"manage:tenant\", \"read:user\", \"write:user\", \"delete:user\", \"read:connector\", \"write:connector\", \"delete:connector\"]"
        }
        adminRole.persist()
    }
    
    fun getTenant(id: UUID): Tenant? = Tenant.findById(id)
    
    fun getTenantTree(): List<TenantDto> {
        val rootTenants = Tenant.findRootTenants()
        return rootTenants.map { toDto(it, true) }
    }
    
    fun getTenantChildren(parentId: UUID): List<Tenant> = Tenant.findByParentId(parentId)
    
    fun getTenantWithChildren(id: UUID): Tenant? {
        val tenant = Tenant.findById(id) ?: return null
        return tenant
    }
    
    @Transactional
    fun updateTenant(id: UUID, request: UpdateTenantRequest): Tenant? {
        val tenant = Tenant.findById(id) ?: return null
        
        request.name?.let { tenant.name = it }
        request.settings?.let { tenant.settings = objectMapper.writeValueAsString(it) }
        request.quota?.let { tenant.quota = objectMapper.writeValueAsString(it) }
        
        tenant.updatedAt = java.time.Instant.now()
        tenant.persist()
        
        return tenant
    }
    
    @Transactional
    fun deleteTenant(id: UUID): Boolean {
        val tenant = Tenant.findById(id) ?: return false
        tenant.delete()
        return true
    }
    
    fun toDto(tenant: Tenant, includeChildren: Boolean = false): TenantDto {
        val settings = try {
            objectMapper.readValue(tenant.settings, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
        
        val quota = try {
            objectMapper.readValue(tenant.quota, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
        
        val children = if (includeChildren) {
            tenant.children.map { toDto(it, true) }
        } else {
            emptyList()
        }
        
        return TenantDto(
            id = tenant.id.toString(),
            name = tenant.name ?: "",
            parentId = tenant.parent?.id.toString(),
            settings = settings,
            quota = quota,
            createdAt = tenant.createdAt,
            updatedAt = tenant.updatedAt,
            children = children
        )
    }
}
