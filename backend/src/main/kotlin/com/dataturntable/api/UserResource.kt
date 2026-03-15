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
}
