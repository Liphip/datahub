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
import java.util.UUID

@Path("/data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class DataResource {
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    private val objectMapper = ObjectMapper()

    private fun parseTenantUuid(tenantId: String): UUID {
        return try {
            UUID.fromString(tenantId)
        } catch (_: Exception) {
            throw WebApplicationException(
                Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
            )
        }
    }

    private fun requireValidTableName(table: String?): String {
        val resolved = sanitizeTableName(table ?: "measurements")
        if (resolved.isBlank()) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid table name")).build())
        }
        if ((table ?: "measurements") != resolved) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid table name")).build())
        }
        return resolved
    }

    private fun parseIsoInstant(value: String?, field: String): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw WebApplicationException(
                Response.status(400).entity(mapOf("error" to "Invalid $field timestamp")).build()
            )
        }
    }

    private fun normalizeLimit(limit: Int?): Int {
        val resolved = limit ?: 100
        if (resolved <= 0 || resolved > 10000) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid limit")).build())
        }
        return resolved
    }
    
    @GET
    @Path("/{tenantId}/query")
    fun queryData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        @QueryParam("start") start: String?,
        @QueryParam("end") end: String?,
        @QueryParam("limit") limit: Int?
    ): Response {
        val tenantUuid = parseTenantUuid(tenantId)
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }

        return try {
            val tableName = requireValidTableName(table)
            val limitValue = normalizeLimit(limit)
            val startInstant = parseIsoInstant(start, "start")
            val endInstant = parseIsoInstant(end, "end")

            val tableFullName = "tenant_${tenantId.replace("-", "_")}_${tableName}"
            val sb = StringBuilder("SELECT * FROM $tableFullName")
            val conditions = mutableListOf<String>()
            if (startInstant != null) conditions.add("time >= :start")
            if (endInstant != null) conditions.add("time <= :end")
            if (conditions.isNotEmpty()) sb.append(" WHERE ").append(conditions.joinToString(" AND "))
            sb.append(" ORDER BY time DESC LIMIT :limit")

            val nativeQuery = em.createNativeQuery(sb.toString())
            if (startInstant != null) nativeQuery.setParameter("start", startInstant)
            if (endInstant != null) nativeQuery.setParameter("end", endInstant)
            nativeQuery.setParameter("limit", limitValue)

            val result = nativeQuery.resultList
            Response.ok(mapOf(
                "data" to result,
                "count" to result.size
            )).build()
        } catch (e: WebApplicationException) {
            e.response
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
        val tenantUuid = parseTenantUuid(tenantId)
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }

        if (data.isEmpty()) {
            return Response.status(400).entity(mapOf("error" to "No data to insert")).build()
        }

        return try {
            val tableName = requireValidTableName(table)
            ensureTableExists(tenantId, tableName)
            insertDataIntoTable(tenantId, tableName, data)
            Response.ok(mapOf(
                "inserted" to data.size,
                "table" to tableName
            )).build()
        } catch (e: WebApplicationException) {
            e.response
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Insert failed: ${e.message}")).build()
        }
    }
    
    @GET
    @Path("/{tenantId}/tables")
    fun listTables(@PathParam("tenantId") tenantId: String): Response {
        val tenantUuid = parseTenantUuid(tenantId)
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
        
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
        val tenantUuid = parseTenantUuid(tenantId)
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
        
        val sanitizedTable = try {
            requireValidTableName(table)
        } catch (e: WebApplicationException) {
            return e.response
        }
        
        return try {
            em.createNativeQuery("DROP TABLE IF EXISTS tenant_${tenantId.replace("-", "_")}_$sanitizedTable").executeUpdate()
            Response.noContent().build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Delete failed: ${e.message}")).build()
        }
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
                if (it is Map<*, *> || it is List<*>) objectMapper.writeValueAsString(it) else it.toString()
            } ?: "{}"

            val instant = try {
                Instant.parse(time)
            } catch (_: Exception) {
                throw WebApplicationException(
                    Response.status(400).entity(mapOf("error" to "Invalid time timestamp")).build()
                )
            }

            val insertQuery = em.createNativeQuery(
                """
                INSERT INTO $tableFullName (time, device_id, value, tags, tenant_id)
                VALUES (:time, :deviceId, :value, :tags, :tenantId)
                ON CONFLICT (time, device_id) DO UPDATE SET value = EXCLUDED.value, tags = EXCLUDED.tags
                """.trimIndent()
            )
            insertQuery.setParameter("time", instant)
            insertQuery.setParameter("deviceId", deviceId)
            insertQuery.setParameter("value", formatValue(value))
            insertQuery.setParameter("tags", tags)
            insertQuery.setParameter("tenantId", tenantId)
            insertQuery.executeUpdate()
        }
    }

    private fun formatValue(value: Any?): Double? {
        return when (value) {
            null -> null
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
    
    private fun sanitizeTableName(table: String): String {
        return table.replace(Regex("[^a-zA-Z0-9_]"), "")
    }
}
