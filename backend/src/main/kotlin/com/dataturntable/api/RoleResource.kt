package com.dataturntable.api

import com.dataturntable.dto.*
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class RoleResource {
    
    private val objectMapper = ObjectMapper()
    
    @GET
    fun getRoles(@QueryParam("tenantId") tenantId: String?): Response {
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }
        
        val roles = Role.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(roles.map { toDto(it) }).build()
    }
    
    @GET
    @Path("/{id}")
    fun getRole(@PathParam("id") id: String): Response {
        val role = Role.findById(UUID.fromString(id))
        return if (role != null) {
            Response.ok(toDto(role)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
    
    @POST
    @Transactional
    fun createRole(
        @QueryParam("tenantId") tenantId: String,
        @Valid request: CreateRoleRequest
    ): Response {
        val tenant = Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val role = Role().apply {
            this.tenant = tenant
            this.name = request.name
            this.description = request.description
            this.abilities = objectMapper.writeValueAsString(request.abilities)
            this.isDefault = request.isDefault
        }
        
        role.persist()
        return Response.status(201).entity(toDto(role)).build()
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun updateRole(
        @PathParam("id") id: String,
        @Valid request: UpdateRoleRequest
    ): Response {
        val role = Role.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()
        
        request.name?.let { role.name = it }
        request.description?.let { role.description = it }
        request.abilities?.let { role.abilities = objectMapper.writeValueAsString(it) }
        request.isDefault?.let { role.isDefault = it }
        
        role.updatedAt = java.time.Instant.now()
        role.persist()
        
        return Response.ok(toDto(role)).build()
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun deleteRole(@PathParam("id") id: String): Response {
        val role = Role.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()
        
        if (role.isSystem) {
            return Response.status(400).entity(mapOf("error" to "Cannot delete system role")).build()
        }
        
        role.delete()
        return Response.noContent().build()
    }
    
    private fun toDto(role: Role): RoleDto {
        val abilities = try {
            val list = mutableListOf<String>()
            val arr = role.abilities
            val start = arr.indexOf('[')
            val end = arr.indexOf(']')
            if (start >= 0 && end > start) {
                arr.substring(start + 1, end).split(",").forEach { s ->
                    list.add(s.trim().removeSurrounding("\""))
                }
            }
            list
        } catch (e: Exception) { emptyList<String>() }
        
        return RoleDto(
            id = role.id.toString(),
            tenantId = role.tenant?.id.toString(),
            name = role.name ?: "",
            description = role.description,
            abilities = abilities,
            isDefault = role.isDefault,
            isSystem = role.isSystem
        )
    }
}
