package com.dataturntable.api

import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import io.quarkus.security.Authenticated
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class RoleResource {
    @PersistenceContext
    lateinit var em: EntityManager
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val roles = if (tenantId != null) {
            Role.findByTenantId(UUID.fromString(tenantId))
        } else {
            PanacheEntityBase.find<Role>("1=1").list()
        }
        
        return Response.ok(roles.map { mapOf(
            "id" to it.id,
            "tenantId" to it.tenant?.id,
            "name" to it.name,
            "description" to it.description,
            "abilities" to it.abilities,
            "isDefault" to it.isDefault,
            "isSystem" to it.isSystem,
            "createdAt" to it.createdAt?.toString()
        ) }).build()
    }
    
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
        val name = request["name"] as? String ?: return Response.status(400)
            .entity(mapOf("error" to "Name is required"))
            .build()
        
        val tenant = if (tenantId != null) {
            em.find(Tenant::class.java, UUID.fromString(tenantId))
        } else {
            null
        }
        
        val role = Role().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.abilities = (request["abilities"] ?: emptyList<String>()).toString()
            this.isDefault = request["isDefault"] as? Boolean ?: false
            this.isSystem = false
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        role.persist()
        
        return Response.status(201).entity(mapOf(
            "id" to role.id,
            "name" to role.name,
            "tenantId" to role.tenant?.id,
            "abilities" to role.abilities
        )).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val role = Role.findById(UUID.fromString(id))
        return if (role != null) {
            Response.ok(mapOf(
                "id" to role.id,
                "tenantId" to role.tenant?.id,
                "name" to role.name,
                "description" to role.description,
                "abilities" to role.abilities,
                "isDefault" to role.isDefault,
                "isSystem" to role.isSystem,
                "createdAt" to role.createdAt?.toString()
            )).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val role = Role.findById(UUID.fromString(id))
        return if (role != null) {
            if (role.isSystem) {
                return Response.status(403)
                    .entity(mapOf("error" to "Cannot modify system role"))
                    .build()
            }
            
            request["name"]?.let { role.name = it as String }
            request["description"]?.let { role.description = it as String }
            request["abilities"]?.let { role.abilities = it.toString() }
            request["isDefault"]?.let { role.isDefault = it as Boolean }
            role.updatedAt = java.time.Instant.now()
            role.persist()
            
            Response.ok(mapOf(
                "id" to role.id,
                "name" to role.name,
                "abilities" to role.abilities
            )).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val role = Role.findById(UUID.fromString(id))
        return if (role != null) {
            if (role.isSystem) {
                return Response.status(403)
                    .entity(mapOf("error" to "Cannot delete system role"))
                    .build()
            }
            role.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
}
