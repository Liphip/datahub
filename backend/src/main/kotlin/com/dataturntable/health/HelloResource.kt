package com.dataturntable.health

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/hello")
class HelloResource {
    
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    fun hello(): Response {
        return Response.ok("Hello World").build()
    }
}
