package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
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
class ConnectorResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    private fun createNonMemberToken(): String {
        val email = "connector-outsider-${java.util.UUID.randomUUID()}@example.com"
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
            "port" to 1883,
            "password" to "supersecret",
            "auth" to listOf(
                mapOf("apiKey" to "nested-secret", "enabled" to true)
            )
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

        val createResponse = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Patch Config Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf("host" to "persist.local", "password" to "secret")
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val id = createResponse.getString("id")

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
            .body("referenceCount", equalTo(1))
            .body("references[0].pipelineName", equalTo("Connector Reference Pipeline"))
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
    fun testTestConnectorValidatesConfig() {
        val token = TestAuthHelpers.createAdminToken()

        val connectorId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Bad MQTT Config",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to mapOf("broker" to "mqtt://localhost:1883")
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
            .body("missing", hasItem("topic"))
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
            .body("referenceCount", equalTo(1))
            .body("references[0].name", equalTo("Guarded Connector"))
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
}
