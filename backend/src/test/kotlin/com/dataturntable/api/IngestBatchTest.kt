package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** IMP-01: the batch form queues every record of a JSON array as its own run. */
@QuarkusTest
class IngestBatchTest {

    private val token by lazy { TestAuthHelpers.createAdminToken() }
    private fun api() = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)

    private fun tenant(): String {
        val root = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        return api().body(mapOf("name" to "batch-${UUID.randomUUID()}", "parentId" to root))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")
    }

    @jakarta.inject.Inject
    lateinit var directory: com.dataturntable.engine.PipelineDirectory

    /**
     * Regression for a CI failure: the periodic directory refresh cached a pipeline as inactive
     * moments before it was activated, and ingest refused records for up to one refresh interval.
     * A fire-and-forget sender does not resend a refused record.
     */
    @Test
    fun `a pipeline accepts records immediately after activation even when the directory cached it as inactive`() {
        val tenantId = tenant()
        api().post("/api/internal/v1/metadata/templates/iot/install?tenantId=$tenantId").then().statusCode(200)
        val id = api().body(mapOf("name" to "Late ${UUID.randomUUID()}", "graphDefinition" to mapOf(
            "nodes" to listOf(mapOf("id" to "in", "type" to "IMPORT"), mapOf("id" to "save", "type" to "EXPORT",
                "saveMetadata" to mapOf("type" to "device", "match" to mapOf("scheme" to "device_id", "field" to "devEUI")))),
            "edges" to listOf(mapOf("source" to "in", "target" to "save")))))
            .post("/api/internal/v1/pipelines?tenantId=$tenantId").then().statusCode(201).extract().jsonPath().getString("id")
        val apiKey = key(tenantId, id)

        directory.refresh() // what the scheduler does: the pipeline is now cached as inactive
        batch(id, apiKey, listOf(mapOf("v" to 1))).then().statusCode(400)

        api().body(mapOf("isActive" to true)).patch("/api/internal/v1/pipelines/$id").then().statusCode(200)
        batch(id, apiKey, listOf(mapOf("v" to 1))).then().statusCode(202)
    }

    private fun pipeline(tenantId: String): String {
        api().post("/api/internal/v1/metadata/templates/iot/install?tenantId=$tenantId").then().statusCode(200)
        val id = api().body(mapOf("name" to "Batch ${UUID.randomUUID()}", "graphDefinition" to mapOf(
            "nodes" to listOf(mapOf("id" to "in", "type" to "IMPORT"), mapOf("id" to "save", "type" to "EXPORT",
                "saveMetadata" to mapOf("type" to "device", "match" to mapOf("scheme" to "device_id", "field" to "devEUI")))),
            "edges" to listOf(mapOf("source" to "in", "target" to "save")))))
            .post("/api/internal/v1/pipelines?tenantId=$tenantId").then().statusCode(201).extract().jsonPath().getString("id")
        api().body(mapOf("isActive" to true)).patch("/api/internal/v1/pipelines/$id").then().statusCode(200)
        return id
    }

    private fun key(tenantId: String, pipelineId: String): String =
        api().body(mapOf("name" to "batch key", "pipelineIds" to listOf(pipelineId)))
            .post("/api/internal/v1/api-keys?tenantId=$tenantId").then().statusCode(201).extract().jsonPath().getString("key")

    private fun batch(pipelineId: String, key: String?, records: List<Map<String, Any>>, idempotencyKey: String? = null) =
        given().contentType(ContentType.JSON)
            .apply { if (key != null) header("X-API-Key", key) }
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .body(records)
            .post("/api/ingest/v1/ingest/$pipelineId/batch")

    @Test
    fun `a batch queues one run per record and a resend with the same key maps to the same runs`() {
        val tenantId = tenant()
        val pipelineId = pipeline(tenantId)
        val apiKey = key(tenantId, pipelineId)
        val records = listOf(mapOf("v" to 1), mapOf("v" to 2), mapOf("v" to 3))

        val first = batch(pipelineId, apiKey, records, "batch-42").then().statusCode(202)
            .body("accepted", equalTo(3)).extract().jsonPath().getList<String>("runIds")
        assertEquals(3, first.toSet().size, "each record gets its own run")

        val resent = batch(pipelineId, apiKey, records, "batch-42").then().statusCode(202)
            .extract().jsonPath().getList<String>("runIds")
        assertEquals(first, resent, "the same Idempotency-Key and position yield the same run id")

        val other = batch(pipelineId, apiKey, records, "batch-43").then().statusCode(202)
            .extract().jsonPath().getList<String>("runIds")
        assertNotEquals(first, other)
    }

    @Test
    fun `a batch needs the same credential as a single record and rejects an empty array`() {
        val tenantId = tenant()
        val pipelineId = pipeline(tenantId)
        val apiKey = key(tenantId, pipelineId)

        batch(pipelineId, null, listOf(mapOf("v" to 1))).then().statusCode(401)
        batch(pipelineId, "dt_wrong", listOf(mapOf("v" to 1))).then().statusCode(401)
        batch(pipelineId, apiKey, emptyList()).then().statusCode(400)
        batch(UUID.randomUUID().toString(), apiKey, listOf(mapOf("v" to 1))).then().statusCode(404)
    }
}
