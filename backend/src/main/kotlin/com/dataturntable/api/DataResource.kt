package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

@Path("/data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class DataResource {
    
    @Inject
    lateinit var userService: UserService
    
    private val objectMapper = ObjectMapper()
    
    @GET
    @Path("/{tenantId}/query")
    fun queryData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String,
        @QueryParam("start") start: String? = null,
        @QueryParam("end") end: String? = null,
        @QueryParam("deviceId") deviceId: String? = null,
        @QueryParam("limit") limit: Int? = 1000,
        @QueryParam("offset") offset: Int? = 0
    ): Response {
        // In production, this would query TimescaleDB
        // For now, return empty results
        
        if (table.isNullOrBlank()) {
            return Response.status(400).entity(mapOf("error" to "table parameter is required")).build()
        }
        
        // Validate tenant access
        // In production, check user permissions
        
        val query = buildQuery(table, tenantId, start, end, deviceId, limit, offset)
        
        return Response.ok(mapOf(
            "data" to emptyList<Map<String, Any>>(),
            "query" to query,
            "count" to 0
        )).build()
    }
    
    @POST
    @Path("/{tenantId}/insert")
    @Transactional
    fun insertData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String,
        @QueryParam("truncate") truncate: Boolean? = false,
        request: List<Map<String, Any>>
    ): Response {
        if (table.isNullOrBlank()) {
            return Response.status(400).entity(mapOf("error" to "table parameter is required")).build()
        }
        
        if (request.isEmpty()) {
            return Response.status(400).entity(mapOf("error" to "Request body is empty")).build()
        }
        
        // In production, insert into TimescaleDB
        // Example:
        // INSERT INTO ? (time, device_id, value, tags)
        // VALUES (?, ?, ?, ?)
        
        return Response.ok(mapOf(
            "inserted" to request.size,
            "table" to table
        )).build()
    }
    
    @POST
    @Path("/{tenantId}/tables")
    @Transactional
    fun createTable(
        @PathParam("tenantId") tenantId: String,
        @Valid request: CreateTableRequest
    ): Response {
        // In production, create hypertable in TimescaleDB
        // Example SQL:
        // CREATE TABLE measurements (
        //   time TIMESTAMPTZ NOT NULL,
        //   device_id TEXT NOT NULL,
        //   value DOUBLE PRECISION,
        //   tags JSONB
        // );
        // SELECT create_hypertable('measurements', 'time');
        
        return Response.status(201).entity(mapOf(
            "table" to request.name,
            "columns" to request.columns
        )).build()
    }
    
    @GET
    @Path("/{tenantId}/tables")
    fun listTables(@PathParam("tenantId") tenantId: String): Response {
        // In production, query information_schema.tables
        
        return Response.ok(mapOf(
            "tables" to listOf(
                mapOf("name" to "measurements", "type" to "hypertable")
            )
        )).build()
    }
    
    @GET
    @Path("/{tenantId}/tables/{table}/columns")
    fun listColumns(
        @PathParam("tenantId") tenantId: String,
        @PathParam("table") table: String
    ): Response {
        // In production, query information_schema.columns
        
        return Response.ok(mapOf(
            "table" to table,
            "columns" to listOf(
                mapOf("name" to "time", "type" to "timestamptz"),
                mapOf("name" to "device_id", "type" to "text"),
                mapOf("name" to "value", "type" to "double precision"),
                mapOf("name" to "tags", "type" to "jsonb")
            )
        )).build()
    }
    
    private fun buildQuery(
        table: String,
        tenantId: String,
        start: String?,
        end: String?,
        deviceId: String?,
        limit: Int?,
        offset: Int?
    ): String {
        val sb = StringBuilder()
        sb.append("SELECT * FROM $table WHERE tenant_id = '$tenantId'")
        
        if (start != null) {
            sb.append(" AND time >= '$start'")
        }
        
        if (end != null) {
            sb.append(" AND time <= '$end'")
        }
        
        if (deviceId != null) {
            sb.append(" AND device_id = '$deviceId'")
        }
        
        sb.append(" ORDER BY time DESC")
        
        if (limit != null) {
            sb.append(" LIMIT $limit")
        }
        
        if (offset != null && offset > 0) {
            sb.append(" OFFSET $offset")
        }
        
        return sb.toString()
    }
}

data class CreateTableRequest(
    val name: String,
    val columns: List<ColumnDefinition>,
    val ifNotExists: Boolean = true
)

data class ColumnDefinition(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val isTag: Boolean = false
)

data class QueryRequest(
    val sql: String,
    val params: Map<String, Any>? = null
)
