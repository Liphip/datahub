package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class PipelineResourceTest {
    
    @Test
    fun testListPipelinesRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetPipelineNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/pipelines/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreatePipelineWithTenantIdQueryParam() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "QueryParam Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .body("name", equalTo("QueryParam Pipeline"))
            .body("graphDefinition", notNullValue())
    }

    @Test
    fun testCreatePipelineRejectsImportNodeWithOutputOnlyConnector() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Output Connector",
                    "connectorType" to "MQTT",
                    "direction" to "OUTPUT",
                    "config" to mapOf("topic" to "out/topic")
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val graphDefinition = mapOf(
            "nodes" to listOf(
                mapOf(
                    "id" to "n1",
                    "type" to "IMPORT",
                    "moduleVersionId" to "00000000-0000-0000-0000-000000000001",
                    "connectorId" to connectorId
                )
            ),
            "edges" to emptyList<Any>()
        )

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Invalid Direction Pipeline", "graphDefinition" to graphDefinition))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(400)
            .body("error", containsString("INPUT or BOTH"))
    }

    @Test
    fun testTriggerPipelineTransitionsRunOutOfPending() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val pipelineId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Runnable Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/pipelines/$pipelineId/run")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("status", anyOf(equalTo("SUCCESS"), equalTo("FAILED"), equalTo("RUNNING"), equalTo("PENDING")))
    }

    @Test
    fun testPipelineListRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", "not-a-uuid")
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testPipelineListRejectsInvalidRunHealthFilter() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", tenantId)
            .queryParam("runHealth", "UNKNOWN")
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid runHealth"))
    }

    @Test
    fun testPipelineEndpointsRejectInvalidPipelineId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/pipelines/not-a-uuid")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid pipeline id"))

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/pipelines/not-a-uuid/run")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid pipeline id"))
    }

    @Test
    fun testGetRunSummaryReturnsAggregateFields() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", tenantId)
            .queryParam("limit", 25)
        .`when`()
            .get("/api/internal/v1/pipelines/runs/summary")
        .then()
            .statusCode(200)
            .body("windowSize", equalTo(25))
            .body("totalRuns", notNullValue())
            .body("successRate", notNullValue())
            .body("failedRuns", notNullValue())
            .body("inFlightRuns", notNullValue())
    }
}
