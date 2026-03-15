package com.dataturntable.api

import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class RoleResource {
    @GET
    fun list(): Response {
        return Response.ok(mapOf("message" to "Role API stub")).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        return Response.ok(mapOf("id" to id)).build()
    }
    
    @POST
    fun create(request: Map<String, Any>): Response {
        return Response.status(201).entity(mapOf("message" to "Created")).build()
    }
}
