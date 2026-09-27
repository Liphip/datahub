package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorLeaseService
import com.dataturntable.security.EncryptionUtils
import com.dataturntable.tenant.Tenant
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import io.quarkus.elytron.security.common.BcryptUtil

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class ConnectorResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    @jakarta.inject.Inject
    lateinit var leaseService: ConnectorLeaseService

    @AfterEach
    fun cleanupAdHocUsers() {
        QuarkusTransaction.requiringNew().run {
            val userIds = em.createQuery("SELECT u.id FROM User u WHERE u.email LIKE 'connector-outsider-%@example.com'", java.util.UUID::class.java).resultList
            if (userIds.isEmpty()) return@run
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
            try {
                em.createQuery("DELETE FROM Membership m WHERE m.authUser.id IN :ids").setParameter("ids", userIds).executeUpdate()
                em.createQuery("DELETE FROM User u WHERE u.id IN :ids").setParameter("ids", userIds).executeUpdate()
            } finally {
                em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
            }
        }
    }

    private fun createNonMemberToken(): String {
        val email = "connector-outsider-${java.util.UUID.randomUUID()}@example.com"
        val password = "ViewerPass!123"

        QuarkusTransaction.requiringNew().run {
            val user = User().apply {
                this.email = email
                this.passwordHash = BcryptUtil.bcryptHash(password)
                this.isActive = true
                this.createdAt = java.time.Instant.now()
                this.updatedAt = java.time.Instant.now()
            }
            em.persist(user)
            em.flush()
        }

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to email, "password" to password))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")
    }

    private fun resolveTenantId(token: String): String {
        return given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")
    }

    /**
     * Persists a connector with legacy inline-credential config directly, bypassing
     * ConnectorResource's create/update validation. Pre-existing connectors written before
     * secretRefs enforcement remain readable/maskable — only new writes through the API reject
     * inline credentials — so masking behavior on that legacy shape still needs coverage.
     */
    private fun createLegacyConnectorWithInlineConfig(tenantId: String, configJson: String): String {
        return QuarkusTransaction.requiringNew().call<String> {
            val tenant = em.find(Tenant::class.java, java.util.UUID.fromString(tenantId))
            val connector = Connector().apply {
                this.name = "Legacy Inline Config Connector"
                this.tenant = tenant
                this.connectorType = Connector.ConnectorType.MQTT
                this.direction = Connector.Direction.INPUT
                this.config = EncryptionUtils.encrypt(configJson)
                this.isActive = true
                this.createdAt = java.time.Instant.now()
                this.updatedAt = java.time.Instant.now()
            }
            em.persist(connector)
            em.flush()
            connector.id.toString()
        }
    }

    @Test
    fun testListConnectorsRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetConnectorNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/connectors/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testCreateConnectorRequiresAuth() {
        given()
        .contentType("application/json")
        .body(mapOf(
            "name" to "Test Connector",
            "connectorType" to "MQTT",
            "direction" to "INPUT"
        ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testCreateConnectorStoresJsonConfig() {
        val token = TestAuthHelpers.createAdminToken()

        val config = mapOf(
            "host" to "test.local",
            "port" to 1883
        )

        val response = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Test Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to config
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .body("name", equalTo("Test Connector"))
            .extract()
            .body()
            .jsonPath()

        val id = response.getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$id")
        .then()
            .statusCode(200)
            .body("config.host", equalTo("test.local"))
            .body("config.port", equalTo(1883))
    }

    @Test
    fun testCreateConnectorRejectsConfigSentAsJsonString() {
        // Regression: `config` sent as a JSON *string* instead of an object skipped the recursive
        // secretRefs walk entirely (a String hits the `else -> null` branch), so a plaintext
        // password was accepted and persisted. A config is always an object, so reject the shape.
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "String Config Connector",
                "connectorType" to "FTP",
                "direction" to "INPUT",
                "config" to """{"host":"ftp.example.com","username":"svc","password":"plaintext-secret"}"""
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(400)
            .body("error", equalTo("Connector config must be a JSON object"))
    }

    @Test
    fun testUpdateConnectorRejectsConfigSentAsJsonString() {
        // The same bypass existed on the update path, which shares the validator.
        val token = TestAuthHelpers.createAdminToken()
        val id = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Update Shape Connector",
                "connectorType" to "SFTP",
                "direction" to "INPUT",
                "config" to mapOf("host" to "sftp.example.com", "username" to "svc")
            ))
            .`when`()
            .post("/api/internal/v1/connectors")
            .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "config" to """{"host":"sftp.example.com","password":"plaintext-secret"}"""
            ))
        .`when`()
            .patch("/api/internal/v1/connectors/$id")
        .then()
            .statusCode(400)
            .body("error", equalTo("Connector config must be a JSON object"))
    }

    @Test
    fun testCreateConnectorRejectsInlineCredentials() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Inline Credential Connector",
                "connectorType" to "REST",
                "direction" to "INPUT",
                "config" to mapOf(
                    "baseUrl" to "https://api.example.com",
                    "apiKey" to "plaintext-secret"
                )
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(400)
            .body("error", equalTo("Connector credentials must use secretRefs"))
    }

    @Test
    fun testCreateConnectorAllowsCredentialsUnderSecretRefs() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "SecretRefs Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf(
                    "broker" to "mqtt://localhost:1883",
                    "secretRefs" to mapOf(
                        "apiKey" to mapOf("id" to java.util.UUID.randomUUID().toString(), "version" to 1)
                    )
                )
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
    }

    @Test
    fun testGetLegacyInlineConfigConnectorMasksCredentialsOnRead() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val id = createLegacyConnectorWithInlineConfig(
            tenantId,
            """{"host":"test.local","port":1883,"password":"supersecret","auth":[{"apiKey":"nested-secret","enabled":true}]}"""
        )

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$id")
        .then()
            .statusCode(200)
            .body("config.host", equalTo("test.local"))
            .body("config.port", equalTo(1883))
            .body("config.password", equalTo("********"))
            .body("config.auth[0].apiKey", equalTo("********"))
    }

    @Test
    fun testListConnectorsRejectsInvalidDirection() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("direction", "sideways")
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid direction"))
    }

    @Test
    fun testListConnectorsRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", "not-a-uuid")
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testCreateConnectorRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Bad Tenant Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT"
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=bad-tenant")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testPatchEmptyConfigDoesNotOverwriteExistingConfig() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        // Seeded directly (not through create()) since this legacy inline-credential shape is
        // exactly what write-time secretRefs validation now rejects; PATCH with an empty config
        // must still preserve it rather than overwrite it.
        val id = createLegacyConnectorWithInlineConfig(
            tenantId,
            """{"host":"persist.local","password":"secret"}"""
        )

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("config" to emptyMap<String, Any>()))
        .`when`()
            .patch("/api/internal/v1/connectors/$id")
        .then()
            .statusCode(200)

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$id")
        .then()
            .statusCode(200)
            .body("config.host", equalTo("persist.local"))
            .body("config.password", equalTo("********"))
    }
    
    @Test
    fun testDeleteConnectorRequiresAuth() {
        given()
        .`when`()
            .delete("/api/internal/v1/connectors/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }

    @Test
    fun testListConnectorsRejectsUnauthorizedTenantScope() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val outsiderToken = createNonMemberToken()

        given()
            .header("Authorization", "Bearer $outsiderToken")
            .queryParam("tenantId", tenantId)
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testConnectAndDisconnectConnector() {
        val token = TestAuthHelpers.createAdminToken()

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Lifecycle Connector",
                "connectorType" to "REST",
                "direction" to "OUTPUT",
                "config" to mapOf("baseUrl" to "https://example.com")
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .post("/api/internal/v1/connectors/$connectorId/disconnect")
        .then()
            .statusCode(200)
            .body("isActive", equalTo(false))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .post("/api/internal/v1/connectors/$connectorId/connect")
        .then()
            .statusCode(200)
            .body("isActive", equalTo(true))
    }

    @Test
    fun testDisconnectConnectorRequiresForceWhenActivePipelinesReferenceIt() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Disconnect Guard Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/guard/in")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            val tenant = em.find(com.dataturntable.tenant.Tenant::class.java, java.util.UUID.fromString(tenantId))
            val pipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Active Guard Pipeline"
                this.isActive = true
                this.graphDefinition = "{\"nodes\":[{\"id\":\"n-import\",\"type\":\"IMPORT\",\"connectorId\":\"$connectorId\"}],\"edges\":[]}"
            }
            em.persist(pipeline)
            em.flush()
        }

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .post("/api/internal/v1/connectors/$connectorId/disconnect")
        .then()
            .statusCode(409)
            .body("details.requiresForce", equalTo(true))
            .body("details.activeImpactedPipelineCount", equalTo(1))
            .body("details.impactedPipelines[0].pipelineName", equalTo("Active Guard Pipeline"))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("force", true)
        .`when`()
            .post("/api/internal/v1/connectors/$connectorId/disconnect")
        .then()
            .statusCode(200)
            .body("isActive", equalTo(false))
    }

    @Test
    fun testListConnectorsSupportsCombinedFiltersAndSearch() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Temperature MQTT Input",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "description" to "sensor ingress",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/temp/in")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)

        val outputId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Temperature MQTT Output",
                "connectorType" to "MQTT",
                "direction" to "OUTPUT",
                "description" to "sensor egress",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/temp/out")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .post("/api/internal/v1/connectors/$outputId/disconnect")
        .then()
            .statusCode(200)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", tenantId)
            .queryParam("type", "MQTT")
            .queryParam("direction", "OUTPUT")
            .queryParam("isActive", false)
            .queryParam("q", "egress")
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].id", equalTo(outputId))
            .body("[0].name", equalTo("Temperature MQTT Output"))
    }

    @Test
    fun testDeleteConnectorRejectsWhenReferencedByPipelineGraph() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Graph Referenced Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/graph/in")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            val tenant = em.find(com.dataturntable.tenant.Tenant::class.java, java.util.UUID.fromString(tenantId))
            val pipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Connector Reference Pipeline"
                this.graphDefinition = "{\"nodes\":[{\"id\":\"n-import\",\"type\":\"IMPORT\",\"connectorId\":\"$connectorId\"}],\"edges\":[]}"
            }
            em.persist(pipeline)
            em.flush()
        }

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", tenantId)
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(200)
            .body("find { it.id == '$connectorId' }.pipelineReferenceCount", equalTo(1))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .delete("/api/internal/v1/connectors/$connectorId")
        .then()
            .statusCode(409)
            .body("error", equalTo("Connector is used by pipeline graphs"))
            .body("details.referenceCount", equalTo(1))
            .body("details.references[0].pipelineName", equalTo("Connector Reference Pipeline"))
    }

    @Test
    fun testGetConnectorReferences() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Usage Endpoint Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/ref/in")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            val tenant = em.find(com.dataturntable.tenant.Tenant::class.java, java.util.UUID.fromString(tenantId))
            val pipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Reference Endpoint Pipeline"
                this.graphDefinition = "{\"nodes\":[{\"id\":\"n-in\",\"type\":\"IMPORT\",\"connectorId\":\"$connectorId\"}],\"edges\":[]}"
            }
            em.persist(pipeline)
            em.flush()
        }

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId/references")
        .then()
            .statusCode(200)
            .body("connectorId", equalTo(connectorId))
            .body("referenceCount", equalTo(1))
            .body("references[0].pipelineName", equalTo("Reference Endpoint Pipeline"))
            .body("references[0].pipelineIsActive", equalTo(false))
            .body("references[0].nodeType", equalTo("IMPORT"))
    }

    @Test
    fun testGetConnectorImpactIncludesLatestRun() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Impact Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/impact/in")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            val tenant = em.find(com.dataturntable.tenant.Tenant::class.java, java.util.UUID.fromString(tenantId))
            val pipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Impact Pipeline"
                this.isActive = true
                this.graphDefinition = "{\"nodes\":[{\"id\":\"n-in\",\"type\":\"IMPORT\",\"connectorId\":\"$connectorId\"}],\"edges\":[]}"
            }
            em.persist(pipeline)
            em.flush()

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.status = PipelineRunStatus.FAILED
                this.triggerType = TriggerType.MANUAL
                this.startedAt = java.time.Instant.now().minusSeconds(15)
                this.completedAt = java.time.Instant.now().minusSeconds(5)
                this.errorMessage = "simulated"
            }
            em.persist(run)

            val inactivePipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Inactive Impact Pipeline"
                this.isActive = false
                this.graphDefinition = "{\"nodes\":[{\"id\":\"n-inactive\",\"type\":\"IMPORT\",\"connectorId\":\"$connectorId\"}],\"edges\":[]}"
            }
            em.persist(inactivePipeline)
            em.flush()
        }

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("sinceHours", 24)
            .queryParam("activeOnly", true)
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId/impact")
        .then()
            .statusCode(200)
            .body("connectorId", equalTo(connectorId))
            .body("sinceHours", equalTo(24))
            .body("activeOnly", equalTo(true))
            .body("referenceCount", equalTo(2))
            .body("impactedPipelineCount", equalTo(1))
            .body("activeImpactedPipelineCount", equalTo(1))
            .body("highRiskCount", equalTo(1))
            .body("impactedPipelines[0].pipelineName", equalTo("Impact Pipeline"))
            .body("impactedPipelines[0].recentRuns", equalTo(1))
            .body("impactedPipelines[0].recentFailedRuns", equalTo(1))
            .body("impactedPipelines[0].impactPriorityScore", equalTo(100))
            .body("impactedPipelines[0].riskLevel", equalTo("HIGH"))
            .body("impactedPipelines[0].latestFailedRunId", notNullValue())
            .body("impactedPipelines[0].latestRunStatus", equalTo("FAILED"))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("sinceHours", 24)
            .queryParam("activeOnly", false)
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId/impact")
        .then()
            .statusCode(200)
            .body("impactedPipelineCount", equalTo(2))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("sinceHours", 24)
            .queryParam("riskAtLeast", "MEDIUM")
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId/impact")
        .then()
            .statusCode(200)
            .body("riskAtLeast", equalTo("MEDIUM"))
            .body("impactedPipelineCount", equalTo(1))
            .body("impactedPipelines[0].pipelineName", equalTo("Impact Pipeline"))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("sinceHours", 5)
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId/impact")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid sinceHours"))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("sinceHours", 24)
            .queryParam("riskAtLeast", "CRITICAL")
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId/impact")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid riskAtLeast"))
    }

    @Test
    fun testCloneConnectorCreatesDisconnectedCopy() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        // Seeded directly (not through create()) since this legacy inline-credential shape is
        // exactly what write-time secretRefs validation now rejects; cloning/stripping an
        // existing legacy connector's secrets must still work.
        val sourceId = createLegacyConnectorWithInlineConfig(
            tenantId,
            """{"baseUrl":"https://example.com","path":"/ingest","apiKey":"super-secret-key"}"""
        )
        QuarkusTransaction.requiringNew().run {
            val connector = em.find(Connector::class.java, java.util.UUID.fromString(sourceId))
            connector.connectorType = Connector.ConnectorType.REST
            connector.direction = Connector.Direction.OUTPUT
            connector.name = "Clone Source"
            connector.description = "clone me"
        }

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Cloned Connector"))
        .`when`()
            .post("/api/internal/v1/connectors/$sourceId/clone")
        .then()
            .statusCode(201)
            .body("name", equalTo("Cloned Connector"))
            .body("isActive", equalTo(false))
            .body("direction", equalTo("OUTPUT"))
            .body("clonedFromConnectorId", equalTo(sourceId))
            .body("description", equalTo("clone me"))
            .body("secretsStripped", equalTo(true))
            .body("config.baseUrl", equalTo("https://example.com"))
            .body("config.path", equalTo("/ingest"))
            .body("config.apiKey", nullValue())
    }

    @Test
    fun testCloneConnectorAllowsDirectionAndDescriptionOverrides() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val sourceId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Clone Override Source",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "description" to "old description",
                "config" to mapOf("broker" to "mqtt://localhost:1883", "topic" to "tenant/$tenantId/clone/in")
            ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Override Clone",
                "direction" to "OUTPUT",
                "description" to "new description"
            ))
        .`when`()
            .post("/api/internal/v1/connectors/$sourceId/clone")
        .then()
            .statusCode(201)
            .body("name", equalTo("Override Clone"))
            .body("direction", equalTo("OUTPUT"))
            .body("description", equalTo("new description"))

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("direction" to "SIDEWAYS"))
        .`when`()
            .post("/api/internal/v1/connectors/$sourceId/clone")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid direction"))
    }

    @Test
    fun testTestConnectorValidatesConfig() {
        val token = TestAuthHelpers.createAdminToken()

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Bad MQTT Config",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                // Topics live on pipeline nodes; the broker is the only required field.
                "config" to mapOf("clientId" to "no-broker")
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .post("/api/internal/v1/connectors/$connectorId/test")
        .then()
            .statusCode(400)
            .body("details.missing", hasItem("broker"))
    }

    @Test
    fun testGetMqttDefaults() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/mqtt/defaults")
        .then()
            .statusCode(200)
            .body("internalBrokerUrl", notNullValue())
            .body("externalBrokerUrl", notNullValue())
            .body("defaultTopic", notNullValue())
    }

    @Test
    fun testCreateAndRotateMqttBrokerCredential() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val credentialId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Dashboard Reader",
                    "username" to "dash-reader",
                    "allowedTopics" to listOf("dataturntable/events/#", "tenant/${tenantId}/metrics/#")
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .body("username", equalTo("dash-reader"))
            .body("password", notNullValue())
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("password" to "ChangedPass!456"))
        .`when`()
            .post("/api/internal/v1/connectors/mqtt/credentials/$credentialId/rotate-password")
        .then()
            .statusCode(200)
            .body("password", equalTo("ChangedPass!456"))
    }

    @Test
    fun testCreateMqttConnectorRejectsTopicOutsideCredentialAcl() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val credentialId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "ACL Guard",
                    "username" to "acl-guard",
                    "allowedTopics" to listOf("tenant/$tenantId/ingest/#")
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Bad Topic MQTT",
                    "connectorType" to "MQTT",
                    "direction" to "INPUT",
                    "config" to mapOf(
                        "broker" to "mqtt://localhost:1883",
                        "topic" to "other-tenant/topic",
                        "brokerCredentialId" to credentialId
                    )
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(400)
            .body("error", equalTo("MQTT topic is not allowed by broker credential"))
    }

    @Test
    fun testDeleteMqttCredentialRejectsWhenReferencedByConnector() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val credentialId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Delete Guard",
                    "username" to "delete-guard",
                    "allowedTopics" to listOf("tenant/$tenantId/events/#")
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Guarded Connector",
                    "connectorType" to "MQTT",
                    "direction" to "INPUT",
                    "config" to mapOf(
                        "broker" to "mqtt://localhost:1883",
                        "topic" to "tenant/$tenantId/events/ingest",
                        "brokerCredentialId" to credentialId
                    )
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .delete("/api/internal/v1/connectors/mqtt/credentials/$credentialId")
        .then()
            .statusCode(409)
            .body("error", equalTo("Broker credential is in use by MQTT connectors"))
            .body("details.referenceCount", equalTo(1))
            .body("details.references[0].name", equalTo("Guarded Connector"))
    }

    @Test
    fun testListMqttCredentialsIncludesReferenceCount() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val credentialId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Usage Counter",
                    "username" to "usage-counter",
                    "allowedTopics" to listOf("tenant/$tenantId/usage/#")
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Usage Connector",
                    "connectorType" to "MQTT",
                    "direction" to "INPUT",
                    "config" to mapOf(
                        "broker" to "mqtt://localhost:1883",
                        "topic" to "tenant/$tenantId/usage/in",
                        "brokerCredentialId" to credentialId
                    )
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=$tenantId")
        .then()
            .statusCode(201)

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
        .then()
            .statusCode(200)
            .body("find { it.id == '$credentialId' }.referenceCount", equalTo(1))
    }

    @Test
    fun testCreateMqttConnectorWithBrokerCredentialStripsInlineSecrets() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val credentialId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Secret Strip",
                    "username" to "secret-stripper",
                    "allowedTopics" to listOf("tenant/$tenantId/secret/#")
                )
            )
        .`when`()
            .post("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Credential Bound MQTT",
                    "connectorType" to "MQTT",
                    "direction" to "INPUT",
                    "config" to mapOf(
                        "broker" to "mqtt://localhost:1883",
                        "topic" to "tenant/$tenantId/secret/in",
                        "brokerCredentialId" to credentialId,
                        "username" to "should-not-persist",
                        "password" to "should-not-persist"
                    )
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

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$connectorId")
        .then()
            .statusCode(200)
            .body("config.brokerCredentialId", equalTo(credentialId))
            .body("config.username", nullValue())
            .body("config.password", nullValue())
    }

    @Test
    fun testGetLeaseReturnsUnownedWhenNoRuntimeHasClaimedTheConnector() {
        val token = TestAuthHelpers.createAdminToken()

        // REST connectors have no persistent runtime loop and are never auto-leased on
        // creation (unlike MQTT/WebSocket), so they stay unowned until something explicitly
        // acquires the lease.
        val id = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Unleased Connector",
                "connectorType" to "REST",
                "direction" to "INPUT"
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$id/lease")
        .then()
            .statusCode(200)
            .body("owned", equalTo(false))
            .body("ownerInstanceId", nullValue())
            .body("fencingToken", nullValue())
    }

    @Test
    fun testGetLeaseExposesOwnershipAfterAcquisition() {
        val token = TestAuthHelpers.createAdminToken()

        val id = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Leased Connector",
                "connectorType" to "REST",
                "direction" to "INPUT"
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        QuarkusTransaction.requiringNew().run {
            leaseService.acquireOrRenew(java.util.UUID.fromString(id))
        }

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$id/lease")
        .then()
            .statusCode(200)
            .body("owned", equalTo(true))
            .body("ownerInstanceId", equalTo(leaseService.currentInstanceId()))
            .body("fencingToken", notNullValue())
            .body("leaseUntil", notNullValue())
    }

    @Test
    fun testGetLeaseRejectsForeignTenant() {
        val token = TestAuthHelpers.createAdminToken()
        val outsiderToken = createNonMemberToken()

        val id = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Tenant Scoped Lease Connector",
                "connectorType" to "REST",
                "direction" to "INPUT"
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $outsiderToken")
        .`when`()
            .get("/api/internal/v1/connectors/$id/lease")
        .then()
            .statusCode(403)
    }
}
