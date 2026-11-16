package com.dataturntable.api

import com.dataturntable.tenant.Role
import com.dataturntable.audit.AuditService
import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
import com.dataturntable.authorization.AuthorizationGrantService
import com.dataturntable.security.SecurityUtils
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/api/internal/v1/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
/**
 * Defines the class `RoleResource`, the contract implemented by this source unit.
 */
class RoleResource {
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var authorizationGrantService: AuthorizationGrantService

    @Inject
    lateinit var audit: AuditService

    /**
     * Implements `currentUser` for this source unit.
     */
    private fun currentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseUuid(value: String, field: String): UUID? {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseAbilities(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(raw, List::class.java).map { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Implements `abilitiesJson` for this source unit.
     */
    private fun abilitiesJson(raw: Any?): String {
        return try {
            objectMapper.writeValueAsString(raw ?: emptyList<String>())
        } catch (_: Exception) {
            "[]"
        }
    }

    /**
     * Implements `roleToMap` for this source unit.
     */
    private fun roleToMap(role: Role): Map<String, Any?> {
        return mapOf(
            "id" to role.id,
            "tenantId" to role.tenant?.id,
            "name" to role.name,
            "description" to role.description,
            "abilities" to role.definedAbilities(),
            "isDefault" to role.isDefault,
            "isSystem" to role.isSystem,
            "isDisabled" to role.isDisabled,
            "createdAt" to role.createdAt?.toString()
        )
    }
    
    @GET
    /**
     * Retrieves or enumerates the requested state.
     */
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val roles = if (tenantId != null) {
            val tenantUuid = parseUuid(tenantId, "tenantId")
                ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
            if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            em.createQuery("SELECT r FROM Role r WHERE r.tenant.id = :tenantId", Role::class.java)
                .setParameter("tenantId", tenantUuid)
                .resultList
        } else {
            if (!currentUser.isSuperAdmin()) {
                return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
            }
            em.createQuery("from Role", Role::class.java).resultList
        }
        
        return Response.ok(roles.map { roleToMap(it) }).build()
    }
    
    @POST
    @Transactional
    /**
     * Creates or persists the requested state.
     */
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
        val name = request["name"] as? String ?: return Response.status(400)
            .entity(mapOf("error" to "Name is required"))
            .build()

        if (tenantId != null && !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (tenantId != null && !SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        if (tenantId == null && !currentUser.isSuperAdmin()) {
            return Response.status(403).entity(mapOf("error" to "Only superadmin can create global roles")).build()
        }
        
        val tenant = if (tenantId != null) {
            val tenantUuid = parseUuid(tenantId, "tenantId")
                ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
            em.find(Tenant::class.java, tenantUuid)
        } else {
            null
        }
        
        val grantedAbilities = authorizationGrantService.normalize(request["abilities"] as? Collection<*>)
        val role = Role().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.abilities = abilitiesJson(grantedAbilities)
            this.isDefault = request["isDefault"] as? Boolean ?: false
            this.isSystem = false
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        role.persist()
        authorizationGrantService.replaceRoleAbilities(role, grantedAbilities)
        audit.record(
            actorId = currentUser.id,
            tenantId = role.tenant?.id,
            action = "identity.role.created",
            resourceType = "role",
            resourceId = role.id.toString(),
            metadata = mapOf("outcome" to "succeeded", "global" to (role.tenant == null))
        )
        
        return Response.status(201).entity(roleToMap(role)).build()
    }
    
    @GET
    @Path("/{id}")
    /**
     * Retrieves or enumerates the requested state.
     */
    fun get(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val roleId = parseUuid(id, "role id")
            ?: return Response.status(400).entity(mapOf("error" to "Invalid role id")).build()
        val role = em.find(Role::class.java, roleId)
        return if (role != null) {
            val tenantId = role.tenant?.id?.toString()
            if (tenantId == null) {
                if (!currentUser.isSuperAdmin()) {
                    return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
                }
            } else {
                if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) {
                    return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
                }
                if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
                    return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
                }
            }
            Response.ok(roleToMap(role)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    /**
     * Updates the requested state.
     */
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val roleId = parseUuid(id, "role id")
            ?: return Response.status(400).entity(mapOf("error" to "Invalid role id")).build()
        val role = em.find(Role::class.java, roleId)
        return if (role != null) {
            val tenantId = role.tenant?.id?.toString()
            if (tenantId != null && !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (tenantId == null && !currentUser.isSuperAdmin()) {
                return Response.status(403).entity(mapOf("error" to "Only superadmin can change global roles")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            // IAM-05: a built-in template cannot be modified; the one thing that may change is
            // whether it is disabled. To change what it grants, clone it.
            if (role.isSystem && (request.keys - "isDisabled").isNotEmpty()) {
                return Response.status(403)
                    .entity(mapOf("error" to "Cannot modify system role; clone it instead"))
                    .build()
            }
            (request["isDisabled"] as? Boolean)?.let { disable ->
                if (disable && !currentUser.isSuperAdmin() && holdsRole(currentUser, role)) {
                    return Response.status(409)
                        .entity(mapOf("error" to "Cannot disable a role you hold yourself"))
                        .build()
                }
                role.isDisabled = disable
            }
            
            request["name"]?.let { role.name = it as String }
            request["description"]?.let { role.description = it as String }
            request["abilities"]?.let {
                val grantedAbilities = authorizationGrantService.normalize(it as? Collection<*>)
                role.abilities = abilitiesJson(grantedAbilities)
                authorizationGrantService.replaceRoleAbilities(role, grantedAbilities)
            }
            request["isDefault"]?.let { role.isDefault = it as Boolean }
            role.updatedAt = java.time.Instant.now()
            role.persist()
            em.createQuery("UPDATE User u SET u.authorizationVersion = u.authorizationVersion + 1 WHERE EXISTS (SELECT m FROM Membership m WHERE m.authUser = u AND m.role = :role)")
                .setParameter("role", role)
                .executeUpdate()
            audit.record(
                actorId = currentUser.id,
                tenantId = role.tenant?.id,
                action = "identity.role.updated",
                resourceType = "role",
                resourceId = role.id.toString(),
                metadata = mapOf("outcome" to "succeeded")
            )
            
            Response.ok(roleToMap(role)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
    
    private fun holdsRole(user: User, role: Role): Boolean =
        (em.createQuery("SELECT COUNT(m) FROM Membership m WHERE m.authUser = :u AND m.role = :r")
            .setParameter("u", user).setParameter("r", role).singleResult as Number).toLong() > 0

    /**
     * IAM-05: copies a role — typically a built-in template, which cannot be edited — into a new
     * editable role in the same tenant. The copy is never a system role and starts enabled.
     */
    @POST
    @Path("/{id}/clone")
    @Transactional
    fun clone(@PathParam("id") id: String, request: Map<String, Any>?): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val roleId = parseUuid(id, "role id")
            ?: return Response.status(400).entity(mapOf("error" to "Invalid role id")).build()
        val source = em.find(Role::class.java, roleId)
            ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()
        val tenantId = source.tenant?.id?.toString()
        if (tenantId != null && !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (tenantId == null && !currentUser.isSuperAdmin()) {
            return Response.status(403).entity(mapOf("error" to "Only superadmin can clone global roles")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        val name = (request?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "${source.name} (copy)"
        val taken = (em.createQuery("SELECT COUNT(r) FROM Role r WHERE r.name = :n AND ((:t IS NULL AND r.tenant IS NULL) OR r.tenant = :t)")
            .setParameter("n", name).setParameter("t", source.tenant).singleResult as Number).toLong() > 0
        if (taken) return Response.status(409).entity(mapOf("error" to "A role with this name already exists")).build()

        val abilities = authorizationGrantService.normalize(source.definedAbilities())
        val copy = Role().apply {
            this.name = name
            this.tenant = source.tenant
            this.description = source.description
            this.abilities = abilitiesJson(abilities)
            this.isDefault = false
            this.isSystem = false
        }
        copy.persist()
        authorizationGrantService.replaceRoleAbilities(copy, abilities)
        audit.record(
            actorId = currentUser.id,
            tenantId = copy.tenant?.id,
            action = "identity.role.cloned",
            resourceType = "role",
            resourceId = copy.id.toString(),
            metadata = mapOf("outcome" to "succeeded", "sourceRoleId" to source.id.toString())
        )
        return Response.status(201).entity(roleToMap(copy)).build()
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    /**
     * Removes, stops, or releases the requested state.
     */
    fun delete(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val roleId = parseUuid(id, "role id")
            ?: return Response.status(400).entity(mapOf("error" to "Invalid role id")).build()
        val role = em.find(Role::class.java, roleId)
        return if (role != null) {
            if (role.isSystem) {
                return Response.status(403)
                    .entity(mapOf("error" to "Cannot delete system role"))
                    .build()
            }

            val tenantId = role.tenant?.id?.toString()
            if (tenantId != null && !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            val roleTenantId = role.tenant?.id
            role.delete()
            audit.record(
                actorId = currentUser.id,
                tenantId = roleTenantId,
                action = "identity.role.deleted",
                resourceType = "role",
                resourceId = roleId.toString(),
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
}
