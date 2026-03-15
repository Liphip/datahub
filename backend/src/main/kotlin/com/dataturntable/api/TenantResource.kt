package com.dataturntable.api

import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
import io.quarkus.security.Authenticated
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class TenantResource {
    
    @GET
    fun list(): Response {
        val tenants = Tenant.findRootTenants()
        return Response.ok(tenants.map { mapOf("id" to it.id, "name" to it.name) }).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val tenant = Tenant.findById(UUID.fromString(id))
        return if (tenant != null) {
            Response.ok(mapOf("id" to tenant.id, "name" to tenant.name, "settings" to tenant.settings)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @POST
    @Transactional
    fun create(request: Map<String, Any>): Response {
        val tenant = Tenant().apply {
            name = request["name"] as? String
        }
        tenant.persist()
        return Response.status(201).entity(mapOf("id" to tenant.id)).build()
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val tenant = Tenant.findById(UUID.fromString(id))
        return if (tenant != null) {
            tenant.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/children")
    fun getChildren(@PathParam("id") id: String): Response {
        val children = Tenant.findByParentId(UUID.fromString(id))
        return Response.ok(children.map { mapOf("id" to it.id, "name" to it.name) }).build()
    }
}
