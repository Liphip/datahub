package com.dataturntable.api

import io.quarkus.security.Authenticated
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class DataResource {
    
    @GET
    @Path("/{tenantId}/query")
    fun queryData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        @QueryParam("limit") limit: Int?
    ): Response {
        return Response.ok(mapOf(
            "message" to "Data query not yet implemented",
            "tenantId" to tenantId,
            "table" to table
        )).build()
    }
    
    @POST
    @Path("/{tenantId}/insert")
    fun insertData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        data: List<Map<String, Any>>
    ): Response {
        return Response.status(501).entity(mapOf(
            "error" to "Data insert not yet implemented"
        )).build()
    }
    
    @GET
    @Path("/{tenantId}/tables")
    fun listTables(@PathParam("tenantId") tenantId: String): Response {
        return Response.ok(mapOf(
            "tables" to listOf(
                mapOf("name" to "sample_table", "type" to "user_created")
            )
        )).build()
    }
}
