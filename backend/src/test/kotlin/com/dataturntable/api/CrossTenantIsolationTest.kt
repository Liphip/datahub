package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import io.quarkus.elytron.security.common.BcryptUtil
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Regression coverage for the fundamental multi-tenant invariant: grants in a
 * sibling tenant must never authorize reads, writes, or resource discovery in
 * another tenant, even if the granted role has every capability.
 */
@QuarkusTest
class CrossTenantIsolationTest {
    @PersistenceContext
    lateinit var em: EntityManager

    @AfterEach
    fun cleanupAdHocUsers() {
        QuarkusTransaction.requiringNew().run {
            val userIds = em.createQuery(
                "SELECT u.id FROM User u WHERE u.email LIKE 'isolation-%@example.com' OR u.email LIKE 'foreign-membership-%@example.com'",
                UUID::class.java
            ).resultList
            if (userIds.isNotEmpty()) {
                em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
                try {
                    em.createQuery("DELETE FROM Membership m WHERE m.authUser.id IN :ids").setParameter("ids", userIds).executeUpdate()
                    em.createQuery("DELETE FROM User u WHERE u.id IN :ids").setParameter("ids", userIds).executeUpdate()
                } finally {
                    em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
                }
            }
        }
    }

    private fun rootTenantId(token: String): String = given()
        .header("Authorization", "Bearer $token")
    .`when`().get("/api/internal/v1/tenants")
    .then().statusCode(200).extract().jsonPath().getString("[0].id")

    private fun createTenant(token: String, rootId: String, label: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf("name" to "$label ${UUID.randomUUID()}", "parentId" to rootId))
    .`when`().post("/api/internal/v1/tenants")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun tenantAToken(tenantAId: String): String {
        val email = "isolation-${UUID.randomUUID()}@example.com"
        val password = "TenantIsolation!123"
        QuarkusTransaction.requiringNew().run {
            val tenant = em.find(Tenant::class.java, UUID.fromString(tenantAId))
            val role = Role().apply {
                this.tenant = tenant
                name = "Isolation full-access role"
                abilities = "[\"*\"]"
            }
            val user = User().apply {
                this.email = email
                passwordHash = BcryptUtil.bcryptHash(password)
                isActive = true
            }
            em.persist(role)
            em.persist(user)
            em.persist(Membership().apply {
                authUser = user
                this.tenant = tenant
                this.role = role
            })
            em.flush()
        }
        return given().contentType(ContentType.JSON)
            .body(mapOf("email" to email, "password" to password, "tenantId" to tenantAId))
        .`when`().post("/api/internal/v1/auth/login")
            .then().statusCode(200).extract().jsonPath().getString("token")
    }

    private fun createProcessingVersion(token: String, tenantId: String): Pair<String, String> =
        createModuleVersion(token, tenantId, "PROCESSING")

    private fun createModuleVersion(token: String, tenantId: String, moduleType: String): Pair<String, String> {
        val moduleId = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("name" to "Isolation module ${UUID.randomUUID()}", "moduleType" to moduleType, "tenantId" to tenantId))
        .`when`().post("/api/internal/v1/modules")
        .then().statusCode(201).extract().jsonPath().getString("id")
        val versionId = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("code" to "def process(data):\n    return data"))
        .`when`().post("/api/internal/v1/modules/$moduleId/versions")
        .then().statusCode(201).extract().jsonPath().getString("id")
        return moduleId to versionId
    }

    private fun createPipeline(token: String, tenantId: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf(
            "name" to "Isolation pipeline ${UUID.randomUUID()}",
            "graphDefinition" to mapOf("schemaVersion" to 1, "nodes" to emptyList<Any>(), "edges" to emptyList<Any>())
        ))
    .`when`().post("/api/internal/v1/pipelines?tenantId=$tenantId")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun createAlertChannel(token: String, tenantId: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf(
            "tenantId" to tenantId,
            "name" to "Isolation alert channel ${UUID.randomUUID()}",
            "channelType" to "WEBHOOK",
            "config" to mapOf("url" to "https://alerts.example.test/hook")
        ))
    .`when`().post("/api/v1/alerts/channels?tenantId=$tenantId")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun createConnector(token: String, tenantId: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf(
            "tenantId" to tenantId,
            "name" to "Isolation connector ${UUID.randomUUID()}",
            "connectorType" to "REST",
            "direction" to "INPUT",
            "config" to mapOf("baseUrl" to "https://example.com")
        ))
    .`when`().post("/api/internal/v1/connectors?tenantId=$tenantId")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun createMqttCredential(token: String, tenantId: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf(
            "tenantId" to tenantId,
            "name" to "Isolation broker credential ${UUID.randomUUID()}",
            "allowedTopics" to listOf("isolation/test")
        ))
    .`when`().post("/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantId")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun createSecret(token: String, tenantId: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf("name" to "isolation.secret.${UUID.randomUUID()}", "value" to "isolation-value"))
    .`when`().post("/api/internal/v1/secrets?tenantId=$tenantId")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun createRole(token: String, tenantId: String): String = given()
        .header("Authorization", "Bearer $token")
        .contentType(ContentType.JSON)
        .body(mapOf("tenantId" to tenantId, "name" to "Isolation role ${UUID.randomUUID()}", "abilities" to emptyList<String>()))
    .`when`().post("/api/internal/v1/roles?tenantId=$tenantId")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private data class ForeignMembership(val membershipId: String, val userId: String)

    private fun createMembership(tenantId: String): ForeignMembership {
        return QuarkusTransaction.requiringNew().call {
            val tenant = em.find(Tenant::class.java, UUID.fromString(tenantId))
            val role = Role().apply {
                this.tenant = tenant
                name = "Foreign membership role ${UUID.randomUUID()}"
                abilities = "[]"
            }
            val user = User().apply {
                email = "foreign-membership-${UUID.randomUUID()}@example.com"
                passwordHash = BcryptUtil.bcryptHash("not-used")
                isActive = true
            }
            val membership = Membership().apply {
                authUser = user
                this.tenant = tenant
                this.role = role
            }
            em.persist(role)
            em.persist(user)
            em.persist(membership)
            em.flush()
            ForeignMembership(membership.id.toString(), user.id.toString())
        }
    }

    @Test
    fun `tenant A full-access membership cannot discover tenant B resources`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Isolation tenant B")
        val tokenA = tenantAToken(tenantAId)

        listOf(
            "/api/internal/v1/auth-users?tenantId=$tenantBId",
            "/api/internal/v1/roles?tenantId=$tenantBId",
            "/api/internal/v1/modules?tenantId=$tenantBId",
            "/api/internal/v1/pipelines?tenantId=$tenantBId",
            "/api/internal/v1/connectors?tenantId=$tenantBId",
            "/api/v1/alerts/channels?tenantId=$tenantBId",
            "/api/v1/data/$tenantBId/tables"
        ).forEach { path ->
            given().header("Authorization", "Bearer $tokenA")
            .`when`().get(path)
            .then().statusCode(403)
        }
    }

    @Test
    fun `tenant A cannot read tenant B module or embed its version in a pipeline graph`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Graph isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Graph isolation tenant B")
        val (moduleBId, moduleVersionBId) = createProcessingVersion(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().get("/api/internal/v1/modules/$moduleBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().get("/api/internal/v1/modules/$moduleBId/versions")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("versionTag" to "tenant-a-must-not-change-this"))
        .`when`().patch("/api/internal/v1/modules/$moduleBId/versions/$moduleVersionBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(emptyMap<String, Any>())
        .`when`().post("/api/internal/v1/modules/$moduleBId/versions/$moduleVersionBId/default")
        .then().statusCode(403)

        val graph = mapOf(
            "schemaVersion" to 1,
            "nodes" to listOf(mapOf("id" to "foreign-module", "type" to "PROCESSING", "moduleVersionId" to moduleVersionBId)),
            "edges" to emptyList<Any>()
        )
        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Foreign module graph", "graphDefinition" to graph))
        .`when`().post("/api/internal/v1/pipelines?tenantId=$tenantAId")
        .then().statusCode(400)
    }

    @Test
    fun `tenant A cannot mutate or delete tenant B resource by its ID`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Mutation isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Mutation isolation tenant B")
        val (moduleBId, _) = createProcessingVersion(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant A must not rename this"))
        .`when`().patch("/api/internal/v1/modules/$moduleBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/internal/v1/modules/$moduleBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $adminToken")
        .`when`().get("/api/internal/v1/modules/$moduleBId")
        .then().statusCode(200)
            .body("name", org.hamcrest.Matchers.not("Tenant A must not rename this"))
    }

    @Test
    fun `tenant A cannot inspect or cancel tenant B execution`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Execution isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Execution isolation tenant B")
        val (_, moduleVersionBId) = createProcessingVersion(adminToken, tenantBId)
        val executionId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("tenantId" to tenantBId, "moduleVersionId" to moduleVersionBId))
        .`when`().post("/api/internal/v1/executions")
        .then().statusCode(202).extract().jsonPath().getString("id")
        val tokenA = tenantAToken(tenantAId)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().get("/api/internal/v1/executions/$executionId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
        .`when`().post("/api/internal/v1/executions/$executionId/cancel")
        .then().statusCode(403)
    }

    @Test
    fun `tenant A cannot distinguish or mutate tenant B membership or user administration`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Membership isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Membership isolation tenant B")
        val foreignMembership = createMembership(tenantBId)
        val tokenA = tenantAToken(tenantAId)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("abilities" to emptyList<String>()))
        .`when`().patch("/api/internal/v1/tenants/$tenantAId/memberships/${foreignMembership.membershipId}")
        .then().statusCode(404)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/internal/v1/tenants/$tenantAId/memberships/${foreignMembership.membershipId}")
        .then().statusCode(404)

        listOf("get", "patch", "delete").forEach { operation ->
            val request = given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            when (operation) {
                "get" -> request.`when`().get("/api/internal/v1/auth-users/${foreignMembership.userId}?tenantId=$tenantBId")
                "patch" -> request.body(mapOf("firstName" to "Tenant A must not change this")).`when`().patch("/api/internal/v1/auth-users/${foreignMembership.userId}?tenantId=$tenantBId")
                else -> request.`when`().delete("/api/internal/v1/auth-users/${foreignMembership.userId}?tenantId=$tenantBId")
            }.then().statusCode(403)
        }
    }

    @Test
    fun `tenant A cannot read tenant B pipeline runs or logs by pipeline ID`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Pipeline isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Pipeline isolation tenant B")
        val pipelineBId = createPipeline(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)
        val arbitraryRunId = UUID.randomUUID()

        listOf(
            "/api/internal/v1/pipelines/$pipelineBId",
            "/api/internal/v1/pipelines/$pipelineBId/runs",
            "/api/internal/v1/pipelines/$pipelineBId/runs/$arbitraryRunId",
            "/api/internal/v1/pipelines/$pipelineBId/runs/$arbitraryRunId/logs"
        ).forEach { path ->
            given().header("Authorization", "Bearer $tokenA")
            .`when`().get(path)
            .then().statusCode(403)
        }
    }

    @Test
    fun `tenant A cannot mutate tenant B alert channels`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Alert isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Alert isolation tenant B")
        val channelBId = createAlertChannel(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Foreign channel"))
        .`when`().patch("/api/v1/alerts/channels/$channelBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/v1/alerts/channels/$channelBId")
        .then().statusCode(403)
    }

    @Test
    fun `tenant A cannot inspect or mutate tenant B connectors or broker credentials`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Connector isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Connector isolation tenant B")
        val connectorBId = createConnector(adminToken, tenantBId)
        val credentialBId = createMqttCredential(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)

        listOf(
            "/api/internal/v1/connectors/$connectorBId",
            "/api/internal/v1/connectors/$connectorBId/status",
            "/api/internal/v1/connectors/$connectorBId/lease",
            "/api/internal/v1/connectors/$connectorBId/revisions",
            "/api/internal/v1/connectors/$connectorBId/references",
            "/api/internal/v1/connectors/mqtt/credentials?tenantId=$tenantBId"
        ).forEach { path ->
            given().header("Authorization", "Bearer $tokenA")
            .`when`().get(path)
            .then().statusCode(403)
        }

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Foreign connector"))
        .`when`().patch("/api/internal/v1/connectors/$connectorBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/internal/v1/connectors/$connectorBId")
        .then().statusCode(403)

        listOf(
            "/api/internal/v1/connectors/$connectorBId/clone",
            "/api/internal/v1/connectors/$connectorBId/connect",
            "/api/internal/v1/connectors/$connectorBId/disconnect",
            "/api/internal/v1/connectors/$connectorBId/test",
            "/api/internal/v1/connectors/$connectorBId/revisions/1/restore"
        ).forEach { path ->
            given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(emptyMap<String, Any>())
            .`when`().post(path)
            .then().statusCode(403)
        }

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Foreign credential"))
        .`when`().patch("/api/internal/v1/connectors/mqtt/credentials/$credentialBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/internal/v1/connectors/mqtt/credentials/$credentialBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("password" to "tenant-a-must-not-set-this"))
        .`when`().post("/api/internal/v1/connectors/mqtt/credentials/$credentialBId/rotate-password")
        .then().statusCode(403)
    }

    @Test
    fun `tenant A cannot list or rotate a tenant B secret`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Secret isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Secret isolation tenant B")
        val secretBId = createSecret(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().get("/api/internal/v1/secrets?tenantId=$tenantBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("value" to "tenant-a-must-not-rotate-this"))
        .`when`().post("/api/internal/v1/secrets/$secretBId/rotate?tenantId=$tenantBId")
        .then().statusCode(403)
    }

    @Test
    fun `tenant A cannot administer tenant B or its roles`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Tenant admin isolation A")
        val tenantBId = createTenant(adminToken, rootId, "Tenant admin isolation B")
        val roleBId = createRole(adminToken, tenantBId)
        val tokenA = tenantAToken(tenantAId)

        listOf(
            "/api/internal/v1/tenants/$tenantBId",
            "/api/internal/v1/roles/$roleBId"
        ).forEach { path ->
            given().header("Authorization", "Bearer $tokenA")
            .`when`().get(path)
            .then().statusCode(403)
        }

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant A must not rename this"))
        .`when`().patch("/api/internal/v1/tenants/$tenantBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/internal/v1/tenants/$tenantBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant A must not rename this"))
        .`when`().patch("/api/internal/v1/roles/$roleBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/internal/v1/roles/$roleBId")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("tenantId" to tenantBId, "name" to "Tenant A must not create this", "abilities" to emptyList<String>()))
        .`when`().post("/api/internal/v1/roles?tenantId=$tenantBId")
        .then().statusCode(403)
    }

    @Test
    fun `tenant A membership cannot be assigned a tenant B role`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Membership role isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Membership role isolation tenant B")
        val roleBId = createRole(adminToken, tenantBId)
        val foreignMembership = createMembership(tenantAId)

        given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("authUserId" to foreignMembership.userId, "roleId" to roleBId))
        .`when`().post("/api/internal/v1/tenants/$tenantAId/memberships")
        .then().statusCode(400)

        given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("roleId" to roleBId))
        .`when`().patch("/api/internal/v1/tenants/$tenantAId/memberships/${foreignMembership.membershipId}")
        .then().statusCode(400)
    }

    @Test
    fun `tenant A cannot make its pipeline reference a tenant B connector`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Pipeline connector isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Pipeline connector isolation tenant B")
        val connectorBId = createConnector(adminToken, tenantBId)
        val (_, moduleVersionAId) = createModuleVersion(adminToken, tenantAId, "IMPORT")

        val graph = mapOf(
            "schemaVersion" to 1,
            "nodes" to listOf(mapOf(
                "id" to "foreign-connector",
                "type" to "IMPORT",
                "moduleVersionId" to moduleVersionAId,
                "connectorId" to connectorBId
            )),
            "edges" to emptyList<Any>()
        )
        given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("name" to "Foreign connector graph", "graphDefinition" to graph))
        .`when`().post("/api/internal/v1/pipelines?tenantId=$tenantAId")
        .then().statusCode(400)
            .body("error", org.hamcrest.Matchers.containsString("another tenant"))
    }

    @Test
    fun `tenant A connector secretRefs cannot resolve a tenant B secret at runtime`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Connector secret isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Connector secret isolation tenant B")
        val secretBId = createSecret(adminToken, tenantBId)

        val connectorAId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf(
                "tenantId" to tenantAId,
                "name" to "Cross-tenant secretRef connector",
                "connectorType" to "REST",
                "direction" to "INPUT",
                "config" to mapOf(
                    "baseUrl" to "https://example.com",
                    "secretRefs" to mapOf("apiKey" to mapOf("id" to secretBId))
                )
            ))
        .`when`().post("/api/internal/v1/connectors?tenantId=$tenantAId")
        .then().statusCode(201).extract().jsonPath().getString("id")

        // The stored reference is inert config; runtime activation must never resolve a foreign secret.
        given().header("Authorization", "Bearer $adminToken")
        .`when`().post("/api/internal/v1/connectors/$connectorAId/connect")
        .then().statusCode(200)
    }

    @Test
    fun `tenant A cannot query or write tenant B data`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = rootTenantId(adminToken)
        val tenantAId = createTenant(adminToken, rootId, "Data isolation tenant A")
        val tenantBId = createTenant(adminToken, rootId, "Data isolation tenant B")
        val tokenA = tenantAToken(tenantAId)

        listOf(
            "/api/v1/data/$tenantBId/query?table=readings",
            "/api/v1/data/$tenantBId/summary?table=readings"
        ).forEach { path ->
            given().header("Authorization", "Bearer $tokenA")
            .`when`().get(path)
            .then().statusCode(403)
        }

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(listOf(mapOf("value" to 1)))
        .`when`().post("/api/v1/data/$tenantBId/insert?table=readings")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA").contentType(ContentType.JSON)
            .body(mapOf("table" to "tenant_a_must_not_register"))
        .`when`().post("/api/v1/data/$tenantBId/tables/register")
        .then().statusCode(403)

        given().header("Authorization", "Bearer $tokenA")
        .`when`().delete("/api/v1/data/$tenantBId/tables/readings")
        .then().statusCode(403)
    }
}
