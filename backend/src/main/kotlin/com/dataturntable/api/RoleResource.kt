package com.dataturntable.api

import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
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

@Path("/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class RoleResource {
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    private fun currentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    private fun parseUuid(value: String, field: String): UUID? {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAbilities(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(raw, List::class.java).map { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun abilitiesJson(raw: Any?): String {
        return try {
            objectMapper.writeValueAsString(raw ?: emptyList<String>())
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun roleToMap(role: Role): Map<String, Any?> {
        return mapOf(
            "id" to role.id,
            "tenantId" to role.tenant?.id,
            "name" to role.name,
            "description" to role.description,
            "abilities" to parseAbilities(role.abilities),
            "isDefault" to role.isDefault,
            "isSystem" to role.isSystem,
            "createdAt" to role.createdAt?.toString()
        )
    }
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val roles = if (tenantId != null) {
            val tenantUuid = parseUuid(tenantId, "tenantId")
                ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
            if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            Role.findByTenantId(tenantUuid)
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
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
        val name = request["name"] as? String ?: return Response.status(400)
            .entity(mapOf("error" to "Name is required"))
            .build()

        if (tenantId != null && !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (tenantId != null && !SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
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
        
        val role = Role().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.abilities = abilitiesJson(request["abilities"])
            this.isDefault = request["isDefault"] as? Boolean ?: false
            this.isSystem = false
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        role.persist()
        
        return Response.status(201).entity(roleToMap(role)).build()
    }
    
    @GET
    @Path("/{id}")
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
                if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
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
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val roleId = parseUuid(id, "role id")
            ?: return Response.status(400).entity(mapOf("error" to "Invalid role id")).build()
        val role = em.find(Role::class.java, roleId)
        return if (role != null) {
            if (role.isSystem) {
                return Response.status(403)
                    .entity(mapOf("error" to "Cannot modify system role"))
                    .build()
            }

            val tenantId = role.tenant?.id?.toString()
            if (tenantId != null && !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            
            request["name"]?.let { role.name = it as String }
            request["description"]?.let { role.description = it as String }
            request["abilities"]?.let { role.abilities = abilitiesJson(it) }
            request["isDefault"]?.let { role.isDefault = it as Boolean }
            role.updatedAt = java.time.Instant.now()
            role.persist()
            
            Response.ok(roleToMap(role)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
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
            if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            role.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
}
