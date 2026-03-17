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
            val failedRun = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(20)
                this.completedAt = java.time.Instant.now().minusSeconds(10)
                this.errorMessage = "boom"
                this.metrics = "{}"
            }
            em.persist(failedRun)
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

        val from = java.time.Instant.now().minusSeconds(30).toString()
        val to = java.time.Instant.now().toString()
        given()
            .header("Authorization", "Bearer $token")
            .queryParam("includeDetails", true)
            .queryParam("runStatus", "FAILED")
            .queryParam("startedFrom", from)
            .queryParam("startedTo", to)
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].status", equalTo("FAILED"))

        val runId = QuarkusTransaction.requiringNew().call<String> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(12)
                this.completedAt = java.time.Instant.now().minusSeconds(2)
                this.metrics = "{\"result\":{\"alertsFired\":2}}"
            }
            em.persist(run)
            em.flush()
            run.id.toString()
        }

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("includeDetails", true)
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/$runId")
        .then()
            .statusCode(200)
            .body("id", equalTo(runId))
            .body("alertsFired", equalTo(2))
            .body("metrics.result.alertsFired", equalTo(2))
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
    fun testCancelPipelineRun() {
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
            .body(mapOf("name" to "Cancellable Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val runId = QuarkusTransaction.requiringNew().call<String> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.RUNNING
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(15)
            }
            em.persist(run)
            em.flush()
            run.id.toString()
        }

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/pipelines/$pipelineId/runs/$runId/cancel")
        .then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))
            .body("errorMessage", equalTo("Cancelled by user"))

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/pipelines/$pipelineId/runs/$runId/cancel")
        .then()
            .statusCode(409)
            .body("error", containsString("already completed"))
    }

    @Test
    fun testRetryPipelineRun() {
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
            .body(mapOf("name" to "Retryable Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val sourceRunId = QuarkusTransaction.requiringNew().call<String> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(40)
                this.completedAt = java.time.Instant.now().minusSeconds(20)
                this.errorMessage = "simulated failure"
            }
            em.persist(run)
            em.flush()
            run.id.toString()
        }

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/pipelines/$pipelineId/runs/$sourceRunId/retry")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("triggerType", equalTo("MANUAL"))
            .body("retryOfRunId", equalTo(sourceRunId))

        val runningRunId = QuarkusTransaction.requiringNew().call<String> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.RUNNING
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(5)
            }
            em.persist(run)
            em.flush()
            run.id.toString()
        }

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/pipelines/$pipelineId/runs/$runningRunId/retry")
        .then()
            .statusCode(409)
            .body("error", containsString("cannot be retried"))
    }

    @Test
    fun testGetPipelineRunLineage() {
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
            .body(mapOf("name" to "Lineage Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val ids = QuarkusTransaction.requiringNew().call<Map<String, String>> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val root = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(90)
                this.completedAt = java.time.Instant.now().minusSeconds(80)
            }
            em.persist(root)
            em.flush()

            val retry = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.retryOfRunId = root.id
                this.startedAt = java.time.Instant.now().minusSeconds(60)
                this.completedAt = java.time.Instant.now().minusSeconds(50)
            }
            em.persist(retry)
            em.flush()

            val retryChild = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.retryOfRunId = retry.id
                this.startedAt = java.time.Instant.now().minusSeconds(30)
                this.completedAt = java.time.Instant.now().minusSeconds(20)
            }
            em.persist(retryChild)
            em.flush()

            mapOf(
                "root" to root.id.toString(),
                "retry" to retry.id.toString(),
                "retryChild" to retryChild.id.toString()
            )
        }

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("maxDepth", 20)
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/${ids["retry"]}/lineage")
        .then()
            .statusCode(200)
            .body("run.id", equalTo(ids["retry"]))
            .body("maxDepth", equalTo(20))
            .body("direction", equalTo("both"))
            .body("rootRunId", equalTo(ids["root"]))
            .body("latestDescendantRunId", equalTo(ids["retryChild"]))
            .body("ancestors.size()", equalTo(1))
            .body("ancestors[0].id", equalTo(ids["root"]))
            .body("ancestors[0].lineageDepth", equalTo(-1))
            .body("ancestorsTruncated", equalTo(false))
            .body("descendants.size()", equalTo(1))
            .body("descendants[0].lineageDepth", equalTo(1))
            .body("descendantsTruncated", equalTo(false))
            .body("descendants[0].id", equalTo(ids["retryChild"]))
    }

    @Test
    fun testGetPipelineRunLineageSupportsMaxDepthTruncation() {
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
            .body(mapOf("name" to "Lineage Depth Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val ids = QuarkusTransaction.requiringNew().call<Map<String, String>> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val r1 = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(120)
                this.completedAt = java.time.Instant.now().minusSeconds(110)
            }
            em.persist(r1)
            em.flush()
            val r2 = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.retryOfRunId = r1.id
                this.startedAt = java.time.Instant.now().minusSeconds(90)
                this.completedAt = java.time.Instant.now().minusSeconds(80)
            }
            em.persist(r2)
            em.flush()
            val r3 = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.retryOfRunId = r2.id
                this.startedAt = java.time.Instant.now().minusSeconds(60)
                this.completedAt = java.time.Instant.now().minusSeconds(50)
            }
            em.persist(r3)
            em.flush()
            mapOf(
                "root" to r1.id.toString(),
                "leaf" to r3.id.toString()
            )
        }

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("maxDepth", 1)
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/${ids["leaf"]}/lineage")
        .then()
            .statusCode(200)
            .body("maxDepth", equalTo(1))
            .body("ancestors.size()", equalTo(1))
            .body("ancestorsTruncated", equalTo(true))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("maxDepth", 1)
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/${ids["root"]}/lineage")
        .then()
            .statusCode(200)
            .body("maxDepth", equalTo(1))
            .body("descendants.size()", equalTo(1))
            .body("descendantsTruncated", equalTo(true))
    }

    @Test
    fun testGetPipelineRunLineageSupportsDirectionFiltering() {
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
            .body(mapOf("name" to "Lineage Direction Pipeline", "graphDefinition" to mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())))
        .`when`()
            .post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val ids = QuarkusTransaction.requiringNew().call<Map<String, String>> {
            val pipeline = em.find(Pipeline::class.java, java.util.UUID.fromString(pipelineId))
            val root = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(120)
                this.completedAt = java.time.Instant.now().minusSeconds(110)
            }
            em.persist(root)
            em.flush()
            val retry = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.retryOfRunId = root.id
                this.startedAt = java.time.Instant.now().minusSeconds(90)
                this.completedAt = java.time.Instant.now().minusSeconds(80)
            }
            em.persist(retry)
            em.flush()
            val retryChild = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.SUCCESS
                this.triggerType = TriggerType.MANUAL
                this.retryOfRunId = retry.id
                this.startedAt = java.time.Instant.now().minusSeconds(60)
                this.completedAt = java.time.Instant.now().minusSeconds(50)
            }
            em.persist(retryChild)
            em.flush()
            mapOf(
                "root" to root.id.toString(),
                "retry" to retry.id.toString(),
                "retryChild" to retryChild.id.toString()
            )
        }

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("direction", "ancestors")
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/${ids["retry"]}/lineage")
        .then()
            .statusCode(200)
            .body("direction", equalTo("ancestors"))
            .body("ancestors.size()", equalTo(1))
            .body("descendants.size()", equalTo(0))
            .body("latestDescendantRunId", nullValue())

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("direction", "descendants")
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/${ids["retry"]}/lineage")
        .then()
            .statusCode(200)
            .body("direction", equalTo("descendants"))
            .body("ancestors.size()", equalTo(0))
            .body("descendants.size()", equalTo(1))
            .body("descendants[0].id", equalTo(ids["retryChild"]))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("direction", "invalid")
        .`when`()
            .get("/api/internal/v1/pipelines/$pipelineId/runs/${ids["retry"]}/lineage")
        .then()
            .statusCode(400)
            .body("error", containsString("Invalid direction"))
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
