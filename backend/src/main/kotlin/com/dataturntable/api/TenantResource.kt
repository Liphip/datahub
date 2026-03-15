package com.dataturntable.api

import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TenantResource {
    @GET
    fun list(): Response {
        return Response.ok(mapOf("message" to "Tenant API stub")).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        return Response.ok(mapOf("id" to id, "name" to "Stub Tenant")).build()
    }
    
    @POST
    fun create(request: Map<String, Any>): Response {
        return Response.status(201).entity(mapOf("message" to "Created")).build()
    }
}
