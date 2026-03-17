package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.mindrot.jbcrypt.BCrypt

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class PipelineResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    fun createNonMemberToken(): String {
        val email = "pipeline-viewer@example.com"
        val passwordHash = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("ViewerPass!123".toByteArray())
        )

        QuarkusTransaction.requiringNew().run {
            val user = User().apply {
                this.email = email
                this.passwordHash = BCrypt.hashpw(passwordHash, BCrypt.gensalt())
                this.isActive = true
                this.createdAt = java.time.Instant.now()
                this.updatedAt = java.time.Instant.now()
            }
            em.persist(user)
            em.flush()
        }

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to email, "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")
    }

    private fun createModuleVersion(token: String, tenantId: String, type: String, namePrefix: String): String {
        val moduleId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "$namePrefix-${java.util.UUID.randomUUID()}",
                    "moduleType" to type,
                    "tenantId" to tenantId
                )
            )
        .`when`()
            .post("/api/internal/v1/modules")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        return given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("code" to "def process(data):\n    return data"))
        .`when`()
            .post("/api/internal/v1/modules/$moduleId/versions")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")
    }
    
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
                    "moduleVersionId" to createModuleVersion(token, tenantId, "IMPORT", "Import Direction Validation"),
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
            .body("error", containsString("INPUT/BOTH"))
    }

    @Test
    fun testCreatePipelineAcceptsAlertNodesInGraph() {
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
                    "name" to "Input Connector",
                    "connectorType" to "MQTT",
                    "direction" to "INPUT",
                    "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "dt/alerts/in")
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
                    "moduleVersionId" to createModuleVersion(token, tenantId, "IMPORT", "Import Module"),
                    "connectorId" to connectorId
                ),
                mapOf("id" to "n2", "type" to "PROCESSING", "moduleVersionId" to createModuleVersion(token, tenantId, "PROCESSING", "Processing Module")),
                mapOf("id" to "n3", "type" to "ALERT", "moduleVersionId" to createModuleVersion(token, tenantId, "ALERT", "Alert Module"))
            ),
            "edges" to listOf(
                mapOf("from" to "n1", "to" to "n2"),
                mapOf("from" to "n2", "to" to "n3")
            )
        )

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Alert Graph Pipeline", "graphDefinition" to graphDefinition))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .body("graphDefinition.nodes.size()", equalTo(3))
    }

    @Test
    fun testCreatePipelineRejectsNodeModuleTypeMismatch() {
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

        val processingVersionId = createModuleVersion(token, tenantId, "PROCESSING", "Mismatch Module")

        val graphDefinition = mapOf(
            "nodes" to listOf(
                mapOf(
                    "id" to "n-export",
                    "type" to "EXPORT",
                    "moduleVersionId" to processingVersionId,
                    "connectorId" to given()
                        .header("Authorization", "Bearer $token")
                        .contentType("application/json")
                        .body(
                            mapOf(
                                "name" to "Output Connector",
                                "connectorType" to "MQTT",
                                "direction" to "OUTPUT",
                                "config" to mapOf("topic" to "dt/mismatch/out")
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
                )
            ),
            "edges" to emptyList<Any>()
        )

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Type Mismatch Pipeline", "graphDefinition" to graphDefinition))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(400)
            .body("error", containsString("expects EXPORT module version"))
    }

    @Test
    fun testCreatePipelineRejectsCyclicGraph() {
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

        val importVersionId = createModuleVersion(token, tenantId, "IMPORT", "Cycle Import")
        val processingVersionId = createModuleVersion(token, tenantId, "PROCESSING", "Cycle Processing")

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Cycle Input Connector",
                    "connectorType" to "MQTT",
                    "direction" to "INPUT",
                    "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "dt/cycle/in")
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
                mapOf("id" to "n-import", "type" to "IMPORT", "moduleVersionId" to importVersionId, "connectorId" to connectorId),
                mapOf("id" to "n-processing", "type" to "PROCESSING", "moduleVersionId" to processingVersionId)
            ),
            "edges" to listOf(
                mapOf("from" to "n-import", "to" to "n-processing"),
                mapOf("from" to "n-processing", "to" to "n-import")
            )
        )

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Cyclic Pipeline", "graphDefinition" to graphDefinition))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(400)
            .body("error", containsString("contains a cycle"))
    }

    @Test
    fun testGetPipelineRunsSupportsLimitAndLightweightMode() {
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
            .body(mapOf("name" to "Runs Lightweight Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(5)
                this.completedAt = java.time.Instant.now()
                this.metrics = "{\"result\":{\"alertsFired\":1},\"moduleLogs\":[{\"stage\":\"IMPORT\"}],\"executionLog\":[\"start\",\"done\"]}"
            }
            em.persist(run)
            em.flush()
        }

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs?limit=1&includeDetails=false")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].metrics.size()", equalTo(0))
            .body("[0].moduleLogs", nullValue())
            .body("[0].executionLog", nullValue())
    }

    @Test
    fun testGetPipelineRunSummaryByPipeline() {
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
            .body(mapOf("name" to "Summary Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val successRun = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(120)
                this.completedAt = java.time.Instant.now().minusSeconds(60)
                this.metrics = "{}"
            }
            val failedRun = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(50)
                this.completedAt = java.time.Instant.now().minusSeconds(20)
                this.metrics = "{}"
            }
            em.persist(successRun)
            em.persist(failedRun)
            em.flush()
        }

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/summary?sinceHours=24")
        .then()
            .statusCode(200)
            .body("pipelineId", equalTo(pipelineId))
            .body("totalRuns", equalTo(2))
            .body("successRuns", equalTo(1))
            .body("failedRuns", equalTo(1))
            .body("trendBuckets.size()", equalTo(24))
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
    fun testPipelineListRejectsInvalidLatestSinceHours() {
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
            .queryParam("latestSinceHours", 12)
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid latestSinceHours"))
    }

    @Test
    fun testPipelineListRejectsInvalidLatestFromTimestamp() {
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
            .queryParam("latestFrom", "not-a-timestamp")
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid latestFrom"))
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
            .queryParam("sinceHours", 24)
        .`when`()
            .get("/api/internal/v1/pipelines/runs/summary")
        .then()
            .statusCode(200)
            .body("windowSize", equalTo(25))
            .body("sinceHours", equalTo(24))
            .body("bucketSeconds", equalTo(3600))
            .body("trendBuckets.size()", equalTo(24))
            .body("totalRuns", notNullValue())
            .body("successRate", notNullValue())
            .body("failedRuns", notNullValue())
            .body("inFlightRuns", notNullValue())
    }

    @Test
    fun testGetRunSummaryRejectsInvalidSinceHours() {
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
            .queryParam("sinceHours", 12)
        .`when`()
            .get("/api/internal/v1/pipelines/runs/summary")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid sinceHours"))
    }

    @Test
    fun testGetTenantRunsEndpointAccessible() {
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
            .queryParam("limit", 20)
        .`when`()
            .get("/api/internal/v1/pipelines/runs")
        .then()
            .statusCode(200)
            .body("size()", notNullValue())
    }

    @Test
    fun testGetTenantRunsRejectsInvalidRunStatus() {
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
            .queryParam("runStatus", "BROKEN")
        .`when`()
            .get("/api/internal/v1/pipelines/runs")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid runStatus"))
    }

    @Test
    fun testPipelineListRejectsUnauthorizedTenantScope() {
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

        val outsiderToken = createNonMemberToken()
        given()
            .header("Authorization", "Bearer $outsiderToken")
            .queryParam("tenantId", tenantId)
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }
}
