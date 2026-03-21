package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.io.File
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.ceil

@Path("/data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class DataResource {

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.data.blob-dir", defaultValue = "/data/blobs")
    lateinit var blobDir: String

    private val objectMapper = ObjectMapper()
    private val tableNameRegex = Regex("^[A-Za-z0-9_]+$")
    private val columnNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val tagFieldRegex = Regex("^[A-Za-z0-9_.-]+$")

    private fun currentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    private fun requireTenantAccess(user: User, tenantId: String, ability: String): Response? {
        if (!SecurityUtils.canAccessTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(user, ability)) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        return null
    }

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

    private fun requireValidColumnName(name: String?, field: String, defaultValue: String): String {
        val resolved = (name ?: defaultValue).trim()
        if (!columnNameRegex.matches(resolved)) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
        return resolved
    }

    private fun requireValidTagField(tagField: String?): String {
        val resolved = (tagField ?: "location").trim()
        if (!tagFieldRegex.matches(resolved)) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid tagField")).build())
        }
        return resolved
    }

    private fun normalizeTopTags(topTags: Int?): Int {
        val resolved = topTags ?: 8
        if (resolved !in 1..25) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid topTags")).build())
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:read")?.let { return it }
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

    @GET
    @Path("/{tenantId}/summary")
    fun summarizeData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        @QueryParam("start") start: String?,
        @QueryParam("end") end: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("numericField") numericField: String?,
        @QueryParam("tagField") tagField: String?,
        @QueryParam("topTags") topTags: Int?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:read")?.let { return it }
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }

        return try {
            val tableName = requireValidTableName(table)
            val limitValue = normalizeLimit(limit)
            val startInstant = parseIsoInstant(start, "start")
            val endInstant = parseIsoInstant(end, "end")
            val numericColumn = requireValidColumnName(numericField, "numericField", "value")
            val tagFieldName = requireValidTagField(tagField)
            val topTagCount = normalizeTopTags(topTags)

            val safeTenant = tenantId.replace("-", "_")
            if (!tableNameRegex.matches(tableName) || !tableNameRegex.matches(safeTenant)) {
                return Response.status(400).entity(mapOf("error" to "Invalid table name")).build()
            }

            val tableFullName = "tenant_${safeTenant}_${tableName}"
            val sb = StringBuilder("SELECT time, $numericColumn, tags FROM $tableFullName")
            val conditions = mutableListOf<String>()
            if (startInstant != null) conditions.add("time >= :start")
            if (endInstant != null) conditions.add("time <= :end")
            if (conditions.isNotEmpty()) sb.append(" WHERE ").append(conditions.joinToString(" AND "))
            sb.append(" ORDER BY time DESC LIMIT :limit")

            val nativeQuery = em.createNativeQuery(sb.toString())
            if (startInstant != null) nativeQuery.setParameter("start", startInstant)
            if (endInstant != null) nativeQuery.setParameter("end", endInstant)
            nativeQuery.setParameter("limit", limitValue)

            val rows = nativeQuery.resultList
            val linePoints = mutableListOf<Pair<Instant, Double>>()
            val tagStats = linkedMapOf<String, MutableMap<String, Double>>()

            for (row in rows) {
                val tuple = row as? Array<*> ?: continue
                if (tuple.size < 2) continue

                val time = toInstant(tuple[0]) ?: continue
                val numericValue = formatValue(tuple[1])
                if (numericValue != null) {
                    linePoints.add(time to numericValue)
                }

                val tags = parseTags(tuple.getOrNull(2))
                val tagValue = tags[tagFieldName]?.toString()?.ifBlank { null }
                if (tagValue != null && numericValue != null) {
                    val bucket = tagStats.getOrPut(tagValue) {
                        mutableMapOf("count" to 0.0, "sum" to 0.0)
                    }
                    bucket["count"] = (bucket["count"] ?: 0.0) + 1
                    bucket["sum"] = (bucket["sum"] ?: 0.0) + numericValue
                }
            }

            val sortedPoints = linePoints.sortedBy { it.first }
            val series = downsampleSeries(sortedPoints, 80)
            val numericCount = sortedPoints.size
            val minValue = sortedPoints.minOfOrNull { it.second }
            val maxValue = sortedPoints.maxOfOrNull { it.second }
            val avgValue = if (numericCount > 0) sortedPoints.sumOf { it.second } / numericCount else null
            val latestPoint = sortedPoints.lastOrNull()

            val bars = tagStats.entries
                .sortedByDescending { it.value["count"] ?: 0.0 }
                .take(topTagCount)
                .map { (label, stats) ->
                    val count = (stats["count"] ?: 0.0).toInt()
                    val sum = stats["sum"] ?: 0.0
                    mapOf(
                        "label" to label,
                        "count" to count,
                        "avgValue" to if (count > 0) sum / count else 0.0
                    )
                }

            Response.ok(
                mapOf(
                    "summary" to mapOf(
                        "rowCount" to rows.size,
                        "numericCount" to numericCount,
                        "min" to minValue,
                        "max" to maxValue,
                        "avg" to avgValue,
                        "latestTime" to latestPoint?.first?.toString(),
                        "latestValue" to latestPoint?.second
                    ),
                    "series" to series,
                    "bars" to bars,
                    "applied" to mapOf(
                        "table" to tableName,
                        "numericField" to numericColumn,
                        "tagField" to tagFieldName,
                        "limit" to limitValue,
                        "start" to startInstant?.toString(),
                        "end" to endInstant?.toString(),
                        "topTags" to topTagCount
                    )
                )
            ).build()
        } catch (e: WebApplicationException) {
            e.response
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Summary query failed: ${e.message}")).build()
        }
    }
    
    @POST
    @Path("/{tenantId}/insert")
    fun insertData(
        @PathParam("tenantId") tenantId: String,
        @QueryParam("table") table: String?,
        data: List<Map<String, Any>>
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:write")?.let { return it }
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:read")?.let { return it }
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:write")?.let { return it }
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

    private fun toInstant(value: Any?): Instant? {
        return when (value) {
            null -> null
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            is Timestamp -> value.toInstant()
            else -> {
                try {
                    Instant.parse(value.toString())
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun parseTags(rawTags: Any?): Map<String, Any?> {
        if (rawTags == null) return emptyMap()
        return try {
            when (rawTags) {
                is Map<*, *> -> rawTags.entries.associate { it.key.toString() to it.value }
                else -> {
                    val json = rawTags.toString()
                    if (json.isBlank()) emptyMap()
                    else objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun downsampleSeries(points: List<Pair<Instant, Double>>, targetPoints: Int): List<Map<String, Any>> {
        if (points.isEmpty()) return emptyList()
        if (points.size <= targetPoints) {
            return points.map { mapOf("time" to it.first.toString(), "value" to it.second) }
        }

        val bucketSize = ceil(points.size.toDouble() / targetPoints.toDouble()).toInt().coerceAtLeast(1)
        return points.chunked(bucketSize).map { chunk ->
            val avg = chunk.sumOf { it.second } / chunk.size
            val timestamp = chunk.last().first
            mapOf(
                "time" to timestamp.toString(),
                "value" to avg
            )
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

    // ── Blob storage ──────────────────────────────────────────────────────────

    @GET
    @Path("/{tenantId}/blobs")
    fun listBlobs(@PathParam("tenantId") tenantId: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:read")?.let { return it }

        return try {
            val rows = em.createNativeQuery(
                "SELECT id, name, content_type, size_bytes, created_at FROM data_blobs WHERE tenant_id = :tid ORDER BY created_at DESC"
            ).setParameter("tid", tenantUuid).resultList
            val blobs = rows.map { row ->
                val r = row as Array<*>
                mapOf("id" to r[0]?.toString(), "name" to r[1], "contentType" to r[2], "sizeBytes" to r[3], "createdAt" to r[4]?.toString())
            }
            Response.ok(mapOf("blobs" to blobs)).build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Failed to list blobs: ${e.message}")).build()
        }
    }

    @POST
    @Path("/{tenantId}/blobs")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    fun uploadBlob(
        @PathParam("tenantId") tenantId: String,
        @FormParam("name") name: String?,
        @FormParam("file") fileBytes: ByteArray?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:write")?.let { return it }
        if (fileBytes == null || fileBytes.isEmpty())
            return Response.status(400).entity(mapOf("error" to "No file provided")).build()
        val fileName = (name ?: "upload-${UUID.randomUUID()}").replace(Regex("[^a-zA-Z0-9._\\-]"), "_")

        return try {
            val dir = File("$blobDir/$tenantId").also { it.mkdirs() }
            val blobId = UUID.randomUUID()
            val storagePath = "${dir.absolutePath}/$blobId-$fileName"
            File(storagePath).writeBytes(fileBytes)

            em.createNativeQuery(
                "INSERT INTO data_blobs (id, tenant_id, name, size_bytes, storage_path, uploaded_by) VALUES (:id, :tid, :name, :size, :path, :uid)"
            ).setParameter("id", blobId)
             .setParameter("tid", tenantUuid)
             .setParameter("name", fileName)
             .setParameter("size", fileBytes.size.toLong())
             .setParameter("path", storagePath)
             .setParameter("uid", currentUser.id)
             .executeUpdate()

            Response.status(201).entity(mapOf("id" to blobId, "name" to fileName, "sizeBytes" to fileBytes.size)).build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Upload failed: ${e.message}")).build()
        }
    }

    @GET
    @Path("/{tenantId}/blobs/{blobId}/download")
    fun downloadBlob(
        @PathParam("tenantId") tenantId: String,
        @PathParam("blobId") blobId: String
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:read")?.let { return it }

        return try {
            val blobUuid = UUID.fromString(blobId)
            val rows = em.createNativeQuery(
                "SELECT name, content_type, storage_path FROM data_blobs WHERE id = :id AND tenant_id = :tid"
            ).setParameter("id", blobUuid).setParameter("tid", tenantUuid).resultList

            if (rows.isEmpty()) return Response.status(404).entity(mapOf("error" to "Blob not found")).build()
            val r = rows[0] as Array<*>
            val storagePath = r[2]?.toString() ?: return Response.status(404).entity(mapOf("error" to "No storage path")).build()
            val file = File(storagePath)
            if (!file.exists()) return Response.status(404).entity(mapOf("error" to "File not found on disk")).build()

            Response.ok(file.readBytes())
                .header("Content-Disposition", "attachment; filename=\"${r[0]}\"")
                .type(r[1]?.toString() ?: MediaType.APPLICATION_OCTET_STREAM)
                .build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Download failed: ${e.message}")).build()
        }
    }

    @DELETE
    @Path("/{tenantId}/blobs/{blobId}")
    @Transactional
    fun deleteBlob(
        @PathParam("tenantId") tenantId: String,
        @PathParam("blobId") blobId: String
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:write")?.let { return it }

        return try {
            val blobUuid = UUID.fromString(blobId)
            val rows = em.createNativeQuery(
                "SELECT storage_path FROM data_blobs WHERE id = :id AND tenant_id = :tid"
            ).setParameter("id", blobUuid).setParameter("tid", tenantUuid).resultList
            if (rows.isEmpty()) return Response.status(404).entity(mapOf("error" to "Blob not found")).build()
            val path = rows[0]?.toString()
            if (!path.isNullOrBlank()) File(path).delete()
            em.createNativeQuery("DELETE FROM data_blobs WHERE id = :id").setParameter("id", blobUuid).executeUpdate()
            Response.noContent().build()
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Delete failed: ${e.message}")).build()
        }
    }

    // ── Table registry ────────────────────────────────────────────────────────

    @POST
    @Path("/{tenantId}/tables/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    fun registerTable(
        @PathParam("tenantId") tenantId: String,
        request: Map<String, Any>
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseTenantUuid(tenantId)
        requireTenantAccess(currentUser, tenantUuid.toString(), "data:write")?.let { return it }

        val tableName = requireValidTableName(request["tableName"] as? String)
        val tableType = (request["tableType"] as? String ?: "timeseries").lowercase()
        if (tableType !in setOf("timeseries", "relational"))
            return Response.status(400).entity(mapOf("error" to "Invalid tableType")).build()

        return try {
            val schemaJson = if (tableType == "relational") {
                objectMapper.writeValueAsString(request["columns"] ?: emptyList<Any>())
            } else null

            // Ensure the physical table exists
            ensureTableExists(tenantId, tableName)
            if (tableType == "relational" && request["columns"] != null) {
                @Suppress("UNCHECKED_CAST")
                val columns = request["columns"] as? List<Map<String, String>> ?: emptyList()
                ensureRelationalTableExists(tenantId, tableName, columns)
            }

            em.createNativeQuery(
                """INSERT INTO data_table_registry (tenant_id, table_name, table_type, schema_def)
                   VALUES (:tid, :name, :type, CAST(:schema AS jsonb))
                   ON CONFLICT (tenant_id, table_name) DO UPDATE SET table_type = EXCLUDED.table_type, schema_def = EXCLUDED.schema_def"""
            ).setParameter("tid", tenantUuid)
             .setParameter("name", tableName)
             .setParameter("type", tableType)
             .setParameter("schema", schemaJson)
             .executeUpdate()

            Response.status(201).entity(mapOf("tableName" to tableName, "tableType" to tableType)).build()
        } catch (e: WebApplicationException) {
            e.response
        } catch (e: Exception) {
            Response.status(500).entity(mapOf("error" to "Register failed: ${e.message}")).build()
        }
    }

    private fun ensureRelationalTableExists(tenantId: String, tableName: String, columns: List<Map<String, String>>) {
        val safeTable = "tenant_${tenantId.replace("-", "_")}_$tableName"
        val colDefs = columns.joinToString(", ") { col ->
            val colName = col["name"]?.replace(Regex("[^a-zA-Z0-9_]"), "") ?: return@joinToString ""
            val colType = when (col["type"]?.uppercase()) {
                "INTEGER" -> "INTEGER"
                "FLOAT" -> "DOUBLE PRECISION"
                "BOOLEAN" -> "BOOLEAN"
                "JSONB" -> "JSONB"
                "TIMESTAMPTZ" -> "TIMESTAMPTZ"
                else -> "TEXT"
            }
            "$colName $colType"
        }
        if (colDefs.isBlank()) return
        em.createNativeQuery(
            "CREATE TABLE IF NOT EXISTS $safeTable (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID DEFAULT '${tenantId}', $colDefs)"
        ).executeUpdate()
    }
}
