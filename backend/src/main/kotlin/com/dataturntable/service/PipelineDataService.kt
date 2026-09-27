package com.dataturntable.service

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.Optional

/**
 * Defines the class `PipelineDataExecutionOutput`, the contract implemented by this source unit.
 */
data class PipelineDataExecutionOutput(
    val result: Any?,
    val stdout: String,
    val stderr: String = "",
    val durationMs: Long,
)

@ApplicationScoped
/**
 * Defines the class `PipelineDataService`, the contract implemented by this source unit.
 */
class PipelineDataService(
    @ConfigProperty(name = "dataturntable.pipeline-data.jdbc.url", defaultValue = "")
    private val configuredJdbcUrl: Optional<String>,
    @ConfigProperty(name = "dataturntable.pipeline-data.jdbc.username", defaultValue = "")
    private val configuredUsername: Optional<String>,
    @ConfigProperty(name = "dataturntable.pipeline-data.jdbc.password", defaultValue = "")
    private val configuredPassword: Optional<String>,
    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    private val defaultJdbcUrl: String,
    @ConfigProperty(name = "quarkus.datasource.username", defaultValue = "")
    private val defaultUsername: String,
    @ConfigProperty(name = "quarkus.datasource.password", defaultValue = "")
    private val defaultPassword: String,
) {
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
    private val identifierRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * Processes the named request, event, or operation.
     */
    fun execute(
        moduleVersion: ModuleVersion,
        moduleType: ModuleType,
        inputData: Any?,
        tenantId: UUID,
    ): PipelineDataExecutionOutput {
        val startedAt = System.currentTimeMillis()
        val config = parseConfig(moduleVersion.config)
        val schema = resolveSchema(config, tenantId)
        val table = requireIdentifier(config["table"]?.toString(), "table")

        return connection().use { connection ->
            connection.autoCommit = false
            ensureSchemaExists(connection, schema)

            val result = when (moduleType) {
                ModuleType.DB_LOAD -> executeLoad(connection, schema, table, config, inputData)
                ModuleType.DB_SAVE -> executeSave(connection, schema, table, config, inputData)
                else -> throw IllegalArgumentException("Unsupported pipeline data module type: $moduleType")
            }
            connection.commit()

            val durationMs = System.currentTimeMillis() - startedAt
            PipelineDataExecutionOutput(
                result = result,
                stdout = objectMapper.writeValueAsString(
                    mapOf(
                        "schema" to schema,
                        "table" to table,
                        "operation" to moduleType.name.lowercase(),
                        "durationMs" to durationMs,
                    )
                ),
                durationMs = durationMs
            )
        }
    }

    /**
     * Processes the named request, event, or operation.
     */
    private fun executeLoad(
        connection: Connection,
        schema: String,
        table: String,
        config: Map<String, Any?>,
        inputData: Any?,
    ): List<Map<String, Any?>> {
        val selectedColumns = ((config["columns"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList())
            .map { requireIdentifier(it, "column") }
        val selectClause = if (selectedColumns.isEmpty()) "*" else selectedColumns.joinToString(", ") { quoteIdentifier(it) }

        val queryInput = asMap(inputData)
        val limit = ((queryInput["limit"] ?: config["limit"]) as? Number)?.toInt()?.coerceIn(1, 10_000) ?: 1000
        val orderBy = requireIdentifier((queryInput["orderBy"] ?: config["orderBy"])?.toString() ?: "created_at", "orderBy", allowFallback = true)
        val orderDirection = ((queryInput["orderDirection"] ?: config["orderDirection"])?.toString()?.uppercase() ?: "DESC")
            .let { if (it == "ASC") "ASC" else "DESC" }

        val filters = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        val whereMap = asMap(queryInput["filters"])
        whereMap.forEach { (key, value) ->
            val column = requireIdentifier(key, "filter")
            filters += "${quoteIdentifier(column)} = ?"
            values += normalizeValue(value)
        }

        val sql = buildString {
            append("SELECT $selectClause FROM ${quoteIdentifier(schema)}.${quoteIdentifier(table)}")
            if (filters.isNotEmpty()) {
                append(" WHERE ")
                append(filters.joinToString(" AND "))
            }
            append(" ORDER BY ${quoteIdentifier(orderBy)} $orderDirection")
            append(" LIMIT ?")
        }

        connection.prepareStatement(sql).use { statement ->
            var index = 1
            values.forEach { bindValue(statement, index++, it) }
            statement.setInt(index, limit)
            statement.executeQuery().use { rs ->
                val meta = rs.metaData
                val rows = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    val row = linkedMapOf<String, Any?>()
                    for (i in 1..meta.columnCount) {
                        row[meta.getColumnLabel(i)] = rs.getObject(i)
                    }
                    rows += row
                }
                return rows
            }
        }
    }

    /**
     * Processes the named request, event, or operation.
     */
    private fun executeSave(
        connection: Connection,
        schema: String,
        table: String,
        config: Map<String, Any?>,
        inputData: Any?,
    ): Map<String, Any?> {
        val rows = normalizeRows(inputData)
        if (rows.isEmpty()) {
            return mapOf("written" to 0, "schema" to schema, "table" to table)
        }

        ensureTableExists(connection, schema, table, rows.first(), config)

        val mode = (config["mode"]?.toString()?.lowercase() ?: "append")
        val columns = rows.flatMap { it.keys }.distinct().map { requireIdentifier(it, "column") }
        val placeholders = columns.joinToString(", ") { "?" }
        val columnSql = columns.joinToString(", ") { quoteIdentifier(it) }

        val sql = if (mode == "upsert") {
            val keyColumns = ((config["keyColumns"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList())
                .map { requireIdentifier(it, "keyColumns") }
            require(keyColumns.isNotEmpty()) { "DB_SAVE upsert mode requires keyColumns" }
            if (isH2(connection)) {
                // H2 has no ON CONFLICT extension; MERGE INTO ... KEY (...) VALUES (...) is its
                // upsert equivalent, matching the column order used for positional binding below.
                "MERGE INTO ${quoteIdentifier(schema)}.${quoteIdentifier(table)} ($columnSql) KEY (${keyColumns.joinToString(", ") { quoteIdentifier(it) }}) VALUES ($placeholders)"
            } else {
                val updateColumns = columns.filterNot { it in keyColumns }
                val updateSql = updateColumns.joinToString(", ") { "${quoteIdentifier(it)} = EXCLUDED.${quoteIdentifier(it)}" }
                "INSERT INTO ${quoteIdentifier(schema)}.${quoteIdentifier(table)} ($columnSql) VALUES ($placeholders) ON CONFLICT (${keyColumns.joinToString(", ") { quoteIdentifier(it) }}) DO UPDATE SET $updateSql"
            }
        } else {
            "INSERT INTO ${quoteIdentifier(schema)}.${quoteIdentifier(table)} ($columnSql) VALUES ($placeholders)"
        }

        connection.prepareStatement(sql).use { statement ->
            rows.forEach { row ->
                columns.forEachIndexed { idx, column ->
                    bindValue(statement, idx + 1, normalizeValue(row[column]))
                }
                statement.addBatch()
            }
            val written = statement.executeBatch().sum()
            return mapOf("written" to written, "schema" to schema, "table" to table, "rows" to rows.size)
        }
    }

    /**
     * Implements `ensureSchemaExists` for this source unit.
     */
    private fun ensureSchemaExists(connection: Connection, schema: String) {
        connection.createStatement().use { stmt ->
            stmt.execute("""CREATE SCHEMA IF NOT EXISTS ${quoteIdentifier(schema)}""")
        }
    }

    /**
     * Implements `ensureTableExists` for this source unit.
     */
    private fun ensureTableExists(
        connection: Connection,
        schema: String,
        table: String,
        sampleRow: Map<String, Any?>,
        config: Map<String, Any?>,
    ) {
        val h2 = isH2(connection)
        val explicitColumns = asMap(config["columnModel"])
        val columns = if (explicitColumns.isNotEmpty()) {
            explicitColumns.mapValues { (_, value) -> mapExplicitType(value?.toString(), h2) }
        } else {
            sampleRow.mapValues { (_, value) -> inferSqlType(value, h2) }
        }
        val timestampType = if (h2) "TIMESTAMP WITH TIME ZONE" else "TIMESTAMPTZ"
        val ddl = buildString {
            append("CREATE TABLE IF NOT EXISTS ${quoteIdentifier(schema)}.${quoteIdentifier(table)} (")
            append(columns.entries.joinToString(", ") { (column, type) -> "${quoteIdentifier(requireIdentifier(column, "column"))} $type" })
            if ("created_at" !in columns.keys) {
                append(", created_at $timestampType DEFAULT NOW()")
            }
            append(")")
        }
        connection.createStatement().use { stmt -> stmt.execute(ddl) }
        columns.forEach { (column, type) ->
            val alter = "ALTER TABLE ${quoteIdentifier(schema)}.${quoteIdentifier(table)} ADD COLUMN IF NOT EXISTS ${quoteIdentifier(requireIdentifier(column, "column"))} $type"
            connection.createStatement().use { stmt -> stmt.execute(alter) }
        }
    }

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun normalizeRows(inputData: Any?): List<Map<String, Any?>> =
        when (inputData) {
            null -> emptyList()
            is Map<*, *> -> listOf(inputData.entries.associate { it.key.toString() to it.value })
            is List<*> -> inputData.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> item.entries.associate { it.key.toString() to it.value }
                    else -> null
                }
            }
            else -> throw IllegalArgumentException("DB_SAVE input must be an object or list of objects")
        }

    /**
     * Implements `asMap` for this source unit.
     */
    private fun asMap(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is String -> try {
                objectMapper.readValue(value, object : TypeReference<Map<String, Any?>>() {})
            } catch (_: Exception) {
                emptyMap()
            }
            else -> emptyMap()
        }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseConfig(raw: String?): Map<String, Any?> =
        if (raw.isNullOrBlank()) emptyMap()
        else try {
            objectMapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
        } catch (_: Exception) {
            emptyMap()
        }

    /**
     * Implements `resolveSchema` for this source unit.
     */
    private fun resolveSchema(config: Map<String, Any?>, tenantId: UUID): String {
        val configured = config["schema"]?.toString()?.trim()
        return if (!configured.isNullOrBlank()) {
            requireIdentifier(configured, "schema")
        } else {
            "tenant_${tenantId.toString().replace("-", "_")}"
        }
    }

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun requireIdentifier(value: String?, field: String, allowFallback: Boolean = false): String {
        val candidate = value?.trim().orEmpty()
        if (candidate.isBlank() && allowFallback) return "created_at"
        require(identifierRegex.matches(candidate)) { "Invalid $field identifier" }
        return candidate
    }

    /**
     * Implements `quoteIdentifier` for this source unit.
     */
    private fun quoteIdentifier(value: String): String = "\"$value\""

    private fun isH2(connection: Connection): Boolean =
        runCatching { connection.metaData.databaseProductName.contains("H2", ignoreCase = true) }.getOrDefault(false)

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    private fun connection(): Connection {
        val url = configuredJdbcUrl.orElse("").ifBlank { defaultJdbcUrl }
        val user = configuredUsername.orElse("").ifBlank { defaultUsername }
        val password = configuredPassword.orElse("").ifBlank { defaultPassword }
        return DriverManager.getConnection(url, user, password)
    }

    /**
     * Implements `inferSqlType` for this source unit.
     */
    private fun inferSqlType(value: Any?, h2: Boolean): String =
        when (value) {
            null -> "TEXT"
            is Int, is Long -> "BIGINT"
            is Float, is Double -> "DOUBLE PRECISION"
            is Boolean -> "BOOLEAN"
            is Instant, is OffsetDateTime, is Timestamp -> if (h2) "TIMESTAMP WITH TIME ZONE" else "TIMESTAMPTZ"
            is Map<*, *>, is List<*> -> if (h2) "CLOB" else "JSONB"
            else -> "TEXT"
        }

    /**
     * Implements `mapExplicitType` for this source unit.
     */
    private fun mapExplicitType(value: String?, h2: Boolean): String =
        when (value?.trim()?.uppercase()) {
            "TEXT", "VARCHAR", "VARCHAR(255)" -> "TEXT"
            "BIGINT", "INTEGER", "INT" -> "BIGINT"
            "DOUBLE", "DOUBLE PRECISION", "FLOAT", "NUMERIC" -> "DOUBLE PRECISION"
            "BOOLEAN", "BOOL" -> "BOOLEAN"
            "TIMESTAMPTZ", "TIMESTAMP" -> if (h2) "TIMESTAMP WITH TIME ZONE" else "TIMESTAMPTZ"
            "JSONB", "JSON" -> if (h2) "CLOB" else "JSONB"
            else -> "TEXT"
        }

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun normalizeValue(value: Any?): Any? =
        when (value) {
            is Instant -> Timestamp.from(value)
            is OffsetDateTime -> Timestamp.from(value.toInstant())
            is Map<*, *>, is List<*> -> objectMapper.writeValueAsString(value)
            else -> value
        }

    /**
     * Implements `bindValue` for this source unit.
     */
    private fun bindValue(statement: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.setNull(index, Types.NULL)
            is Timestamp -> statement.setTimestamp(index, value)
            is Int -> statement.setInt(index, value)
            is Long -> statement.setLong(index, value)
            is Float -> statement.setFloat(index, value)
            is Double -> statement.setDouble(index, value)
            is Boolean -> statement.setBoolean(index, value)
            else -> statement.setObject(index, value)
        }
    }
}
