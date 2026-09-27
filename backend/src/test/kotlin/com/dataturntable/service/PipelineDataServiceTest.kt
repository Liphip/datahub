package com.dataturntable.service

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * DB_SAVE upsert mode previously built PostgreSQL-only `ON CONFLICT ... DO UPDATE` SQL
 * unconditionally, which fails against the H2 test datasource with a SQLGrammarException — this
 * class connects via a plain (non-JTA) JDBC connection, so it's an H2-compatibility bug rather
 * than the JTA rollback-only trap fixed elsewhere, but it meant the upsert path was completely
 * untested and broken in this environment. Exercises the fix's H2 MERGE INTO fallback.
 */
@QuarkusTest
class PipelineDataServiceTest {

    @Inject
    lateinit var pipelineDataService: PipelineDataService

    private fun moduleVersion(config: Map<String, Any?>): ModuleVersion {
        val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
        return ModuleVersion().apply {
            this.config = objectMapper.writeValueAsString(config)
        }
    }

    @Test
    fun `DB_SAVE upsert mode inserts then updates an existing row by key columns`() {
        val tenantId = UUID.randomUUID()
        val table = "upsert_${UUID.randomUUID().toString().replace("-", "_").take(12)}"
        val config = mapOf(
            "table" to table,
            "mode" to "upsert",
            "keyColumns" to listOf("device_id")
        )

        val firstWrite = pipelineDataService.execute(
            moduleVersion = moduleVersion(config),
            moduleType = ModuleType.DB_SAVE,
            inputData = mapOf("device_id" to "device-1", "value" to 10),
            tenantId = tenantId
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (firstWrite.result as Map<String, Any?>)["written"])

        val secondWrite = pipelineDataService.execute(
            moduleVersion = moduleVersion(config),
            moduleType = ModuleType.DB_SAVE,
            inputData = mapOf("device_id" to "device-1", "value" to 99),
            tenantId = tenantId
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (secondWrite.result as Map<String, Any?>)["written"])

        val loaded = pipelineDataService.execute(
            moduleVersion = moduleVersion(mapOf("table" to table)),
            moduleType = ModuleType.DB_LOAD,
            inputData = mapOf("filters" to mapOf("device_id" to "device-1")),
            tenantId = tenantId
        )
        @Suppress("UNCHECKED_CAST")
        val rows = loaded.result as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        assertEquals(99L, (rows.first()["value"] as Number).toLong())
    }

    @Test
    fun `DB_SAVE append mode with JSON and timestamp columns creates and writes the table`() {
        val tenantId = UUID.randomUUID()
        val table = "append_${UUID.randomUUID().toString().replace("-", "_").take(12)}"
        val config = mapOf("table" to table, "mode" to "append")

        val output = pipelineDataService.execute(
            moduleVersion = moduleVersion(config),
            moduleType = ModuleType.DB_SAVE,
            inputData = mapOf(
                "device_id" to "device-2",
                "recorded_at" to java.time.Instant.now(),
                "tags" to mapOf("zone" to "a")
            ),
            tenantId = tenantId
        )

        @Suppress("UNCHECKED_CAST")
        assertEquals(1, (output.result as Map<String, Any?>)["written"])
    }
}
