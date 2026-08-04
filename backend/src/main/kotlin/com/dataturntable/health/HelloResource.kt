package com.dataturntable.health

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/hello")
/**
 * Defines the class `HelloResource`, the contract implemented by this source unit.
 */
class HelloResource {
    
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    /**
     * Implements `hello` for this source unit.
     */
    fun hello(): Response {
        return Response.ok("Hello World").build()
    }
}
