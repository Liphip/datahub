package com.dataturntable.api

import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/modules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ModuleResource {
    @GET
    fun list(): Response {
        return Response.ok(mapOf("message" to "Module API stub")).build()
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
