package com.dataturntable.api

import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant

@Path("/data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class DataResource {
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    private val objectMapper = ObjectMapper()
    
    @GET
    @Path("/{tenantId}/query")
    fun queryData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        @QueryParam("start") start: String?,
        @QueryParam("end") end: String?,
        @QueryParam("limit") limit: Int?
    ): Response {
        val tenant = Tenant.findById(java.util.UUID.fromString(tenantId))
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        
        val tableName = table ?: "measurements"
        val limitValue = limit ?: 100
        
        val query = buildDataQuery(tenantId, tableName, start, end, limitValue)
        
        return try {
            val result = em.createNativeQuery(query).resultList
            Response.ok(mapOf(
                "data" to result,
                "count" to result.size
            )).build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Query failed: ${e.message}")).build()
        }
    }
    
    @POST
    @Path("/{tenantId}/insert")
    fun insertData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        data: List<Map<String, Any>>
    ): Response {
        val tenant = Tenant.findById(java.util.UUID.fromString(tenantId))
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        
        val tableName = table ?: "measurements"
        
        if (data.isEmpty()) {
            return Response.status(400).entity(mapOf("error" to "No data to insert")).build()
        }
        
        return try {
            ensureTableExists(tenantId, tableName)
            insertDataIntoTable(tenantId, tableName, data)
            Response.ok(mapOf(
                "inserted" to data.size,
                "table" to tableName
            )).build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Insert failed: ${e.message}")).build()
        }
    }
    
    @GET
    @Path("/{tenantId}/tables")
    fun listTables(@PathParam("tenantId") tenantId: String): Response {
        val tenant = Tenant.findById(java.util.UUID.fromString(tenantId))
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        
        val query = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public' 
            AND table_name LIKE 'tenant_${tenantId.replace("-", "_")}_%'
        """.trimIndent()
        
        return try {
            val result = em.createNativeQuery(query).resultList
            val tables = result.map { row ->
                val name = row.toString()
                mapOf(
                    "name" to name,
                    "type" to if (name.endsWith("_measurements")) "timeseries" else "relational"
                )
            }
            Response.ok(mapOf("tables" to tables)).build()
        } catch (e: Exception) {
            Response.ok(mapOf("tables" to emptyList<Map<*, *>>())).build()
        }
    }
    
    @DELETE
    @Path("/{tenantId}/tables/{table}")
    fun deleteTable(
        @PathParam("tenantId") tenantId: String,
        @PathParam("table") table: String
    ): Response {
        val tenant = Tenant.findById(java.util.UUID.fromString(tenantId))
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        
        val sanitizedTable = sanitizeTableName(table)
        
        return try {
            em.createNativeQuery("DROP TABLE IF EXISTS tenant_${tenantId.replace("-", "_")}_$sanitizedTable").executeUpdate()
            Response.noContent().build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Delete failed: ${e.message}")).build()
        }
    }
    
    private fun buildDataQuery(
        tenantId: String,
        tableName: String,
        start: String?,
        end: String?,
        limit: Int
    ): String {
        val tableFullName = "tenant_${tenantId.replace("-", "_")}_$tableName"
        
        val sb = StringBuilder()
        sb.append("SELECT * FROM $tableFullName")
        
        val conditions = mutableListOf<String>()
        
        if (start != null) {
            conditions.add("time >= '${formatTimestamp(start)}'")
        }
        if (end != null) {
            conditions.add("time <= '${formatTimestamp(end)}'")
        }
        
        if (conditions.isNotEmpty()) {
            sb.append(" WHERE ")
            sb.append(conditions.joinToString(" AND "))
        }
        
        sb.append(" ORDER BY time DESC")
        sb.append(" LIMIT $limit")
        
        return sb.toString()
    }
    
    private fun ensureTableExists(tenantId: String, tableName: String) {
        val tableFullName = "tenant_${tenantId.replace("-", "_")}_$tableName"
        
        val checkQuery = """
            SELECT COUNT(*) 
            FROM information_schema.tables 
            WHERE table_name = '$tableFullName'
        """.trimIndent()
        
        val count = em.createNativeQuery(checkQuery).singleResult as Number
        if (count.toInt() == 0) {
            val createQuery = """
                CREATE TABLE IF NOT EXISTS $tableFullName (
                    time TIMESTAMPTZ NOT NULL,
                    device_id VARCHAR(255),
                    value DOUBLE PRECISION,
                    tags JSONB,
                    tenant_id UUID DEFAULT '$tenantId',
                    PRIMARY KEY (time, device_id)
                )
            """.trimIndent()
            em.createNativeQuery(createQuery).executeUpdate()
            
            val indexQuery = """
                CREATE INDEX IF NOT EXISTS idx_${tableFullName}_time 
                ON $tableFullName(time DESC)
            """.trimIndent()
            em.createNativeQuery(indexQuery).executeUpdate()
            
            val indexQuery2 = """
                CREATE INDEX IF NOT EXISTS idx_${tableFullName}_device 
                ON $tableFullName(device_id)
            """.trimIndent()
            em.createNativeQuery(indexQuery2).executeUpdate()
        }
    }
    
    private fun insertDataIntoTable(tenantId: String, tableName: String, data: List<Map<String, Any>>) {
        val tableFullName = "tenant_${tenantId.replace("-", "_")}_$tableName"
        
        for (record in data) {
            val time = record["time"]?.toString() ?: Instant.now().toString()
            val deviceId = record["device_id"]?.toString() ?: "unknown"
            val value = record["value"]
            val tags = record["tags"]?.let {
                if (it is Map<*, *>) objectMapper.writeValueAsString(it) else null
            } ?: "{}"
            
            val insertQuery = """
                INSERT INTO $tableFullName (time, device_id, value, tags, tenant_id)
                VALUES ('${formatTimestamp(time)}', '$deviceId', ${formatValue(value)}, '$tags', '$tenantId')
                ON CONFLICT (time, device_id) DO UPDATE SET value = EXCLUDED.value, tags = EXCLUDED.tags
            """.trimIndent()
            
            em.createNativeQuery(insertQuery).executeUpdate()
        }
    }
    
    private fun formatTimestamp(ts: String): String {
        return try {
            val instant = Instant.parse(ts)
            instant.toString()
        } catch (e: Exception) {
            ts
        }
    }
    
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "NULL"
            is Number -> value.toString()
            else -> "'$value'"
        }
    }
    
    private fun sanitizeTableName(table: String): String {
        return table.replace(Regex("[^a-zA-Z0-9_]"), "")
    }
}
