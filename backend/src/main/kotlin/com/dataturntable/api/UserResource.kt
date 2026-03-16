package com.dataturntable.api

import com.dataturntable.auth.User
import io.quarkus.security.Authenticated
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class UserResource {
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String): Response {
        val users = User.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(users.map { mapOf("id" to it.id, "email" to it.email, "firstName" to it.firstName) }).build()
    }
    
    @POST
    @Transactional
    fun create(request: Map<String, Any>): Response {
        val tenantId = request["tenantId"] as? String
        val email = request["email"] as? String ?: return Response.status(400).entity(mapOf("error" to "Email is required")).build()
        val passwordHash = request["passwordHash"] as? String ?: return Response.status(400).entity(mapOf("error" to "Password hash is required")).build()
        
        val tenant = com.dataturntable.tenant.Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val user = User().apply {
            this.tenant = tenant
            this.email = email
            this.passwordHash = passwordHash
            this.firstName = request["firstName"] as? String
            this.lastName = request["lastName"] as? String
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        user.persist()
        
        return Response.status(201).entity(mapOf("id" to user.id, "email" to user.email)).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val user = User.findById(UUID.fromString(id))
        return if (user != null) {
            Response.ok(mapOf("id" to user.id, "email" to user.email, "firstName" to user.firstName)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val user = User.findById(UUID.fromString(id))
        return if (user != null) {
            user.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val user = User.findById(UUID.fromString(id))
        return if (user != null) {
            request["email"]?.let { user.email = it as String }
            request["passwordHash"]?.let { user.passwordHash = it as String }
            request["firstName"]?.let { user.firstName = it as String }
            request["lastName"]?.let { user.lastName = it as String }
            request["isActive"]?.let { user.isActive = it as Boolean }
            user.updatedAt = java.time.Instant.now()
            user.persist()
            Response.ok(mapOf("id" to user.id, "email" to user.email)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
}
