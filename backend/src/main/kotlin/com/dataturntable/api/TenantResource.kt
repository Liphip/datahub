package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.service.TenantService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import java.util.UUID

@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class TenantResource {
    
    @Inject
    lateinit var tenantService: TenantService
    
    @GET
    fun getTenants(@Context securityContext: SecurityContext): Response {
        val tenants = tenantService.getTenantTree()
        return Response.ok(tenants).build()
    }
    
    @GET
    @Path("/{id}")
    fun getTenant(@PathParam("id") id: String): Response {
        val tenant = tenantService.getTenant(UUID.fromString(id))
        return if (tenant != null) {
            Response.ok(tenantService.toDto(tenant, true)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @POST
    @Transactional
    fun createTenant(
        @Valid request: CreateTenantRequest,
        @Context securityContext: SecurityContext
    ): Response {
        // In a real implementation, get current user from security context
        // For now, use a placeholder
        val user = User.findAll().firstResult<User>()
        
        val tenant = tenantService.createTenant(request, request.parentId?.let { UUID.fromString(it) }, user)
        return Response.status(201).entity(tenantService.toDto(tenant)).build()
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun updateTenant(
        @PathParam("id") id: String,
        @Valid request: UpdateTenantRequest
    ): Response {
        val tenant = tenantService.updateTenant(UUID.fromString(id), request)
        return if (tenant != null) {
            Response.ok(tenantService.toDto(tenant)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun deleteTenant(@PathParam("id") id: String): Response {
        val deleted = tenantService.deleteTenant(UUID.fromString(id))
        return if (deleted) {
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/children")
    fun getChildren(@PathParam("id") id: String): Response {
        val children = tenantService.getTenantChildren(UUID.fromString(id))
        return Response.ok(children.map { tenantService.toDto(it) }).build()
    }
}
