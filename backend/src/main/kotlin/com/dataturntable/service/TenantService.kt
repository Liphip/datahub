package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.authorization.AuthorizationGrantService
import com.dataturntable.dto.*
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.*

@ApplicationScoped
/**
 * Defines the class `TenantService`, the contract implemented by this source unit.
 */
class TenantService {
    
    @Inject
    lateinit var userService: UserService

    @Inject
    lateinit var authorizationGrantService: AuthorizationGrantService
    
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
    
    @Transactional
    /**
     * Creates or persists the requested state.
     */
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
    
    /**
     * Creates or persists the requested state.
     */
    fun createDefaultRoles(tenant: Tenant) {
        val viewerRole = Role().apply {
            this.tenant = tenant
            name = "Viewer"
            description = "Default viewer role"
            abilities = "[\"module:read\", \"pipeline:read\", \"data:read\", \"alert:read\", \"connector:read\", \"metadata:read\", \"user:read\"]"
            isDefault = true
        }
        viewerRole.persist()
        authorizationGrantService.replaceRoleAbilities(viewerRole, authorizationGrantService.legacyTokens(viewerRole.abilities))
        
        val editorRole = Role().apply {
            this.tenant = tenant
            name = "Editor"
            description = "Default editor role"
            abilities = "[\"module:read\", \"module:write\", \"pipeline:read\", \"pipeline:write\", \"pipeline:execute\", \"data:read\", \"data:write\", \"alert:read\", \"connector:read\", \"connector:write\", \"metadata:read\", \"metadata:write\", \"user:read\"]"
        }
        editorRole.persist()
        authorizationGrantService.replaceRoleAbilities(editorRole, authorizationGrantService.legacyTokens(editorRole.abilities))
        
        val adminRole = Role().apply {
            this.tenant = tenant
            name = "Tenant Admin"
            description = "Tenant administrator role"
            abilities = "[\"module:read\", \"module:write\", \"module:delete\", \"pipeline:read\", \"pipeline:write\", \"pipeline:execute\", \"data:read\", \"data:write\", \"data:export\", \"alert:read\", \"alert:configure\", \"tenant:manage\", \"metadata:read\", \"metadata:write\", \"user:read\", \"user:write\", \"user:delete\", \"connector:read\", \"connector:write\", \"connector:delete\"]"
        }
        adminRole.persist()
        authorizationGrantService.replaceRoleAbilities(adminRole, authorizationGrantService.legacyTokens(adminRole.abilities))
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getTenant(id: UUID): Tenant? = Tenant.findById(id)
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getTenantTree(): List<TenantDto> {
        val rootTenants = Tenant.findRootTenants()
        return rootTenants.map { toDto(it, true) }
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getTenantChildren(parentId: UUID): List<Tenant> = Tenant.findByParentId(parentId)
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getTenantWithChildren(id: UUID): Tenant? {
        val tenant = Tenant.findById(id) ?: return null
        return tenant
    }
    
    @Transactional
    /**
     * Updates the requested state.
     */
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
    /**
     * Removes, stops, or releases the requested state.
     */
    fun deleteTenant(id: UUID): Boolean {
        val tenant = Tenant.findById(id) ?: return false
        tenant.delete()
        return true
    }
    
    /**
     * Converts a value into the representation required by its caller.
     */
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
