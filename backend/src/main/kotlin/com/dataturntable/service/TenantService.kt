package com.dataturntable.service

import com.dataturntable.authorization.AuthorizationGrantService
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Seeds a new tenant's default role templates (TenantResource, AuthResource's first-run setup).
 * Tenant CRUD itself is handled directly by TenantResource (EntityManager), not here — this class
 * used to also carry create/read/update/delete methods, but nothing called them; they and the
 * request/response DTOs they used (dto/TenantDto.kt) were dead code and were removed.
 */
@ApplicationScoped
class TenantService {

    @Inject
    lateinit var authorizationGrantService: AuthorizationGrantService

    /**
     * Creates the built-in Viewer, Editor and Tenant Admin roles for [tenant], each with a fixed
     * ability set (TEN-... default role templates). Tenant Admin is the role a new tenant's first
     * user receives.
     */
    @Transactional
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
}
