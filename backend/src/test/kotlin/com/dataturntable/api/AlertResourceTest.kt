package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.audit.AuditEvent
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

@QuarkusTest
class AlertResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    @AfterEach
    fun cleanupAdHocUsers() {
        QuarkusTransaction.requiringNew().run {
            val userIds = em.createQuery("SELECT u.id FROM User u WHERE u.email LIKE 'alerts-outsider-%@example.com'", UUID::class.java).resultList
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
        val email = "alerts-outsider-${java.util.UUID.randomUUID()}@example.com"
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
            .getList<Map<String, Any>>("")
            .first()["id"]
            .toString()
    }

    @Test
    fun testListChannelsRequiresAuth() {
        given()
            .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/v1/alerts/channels")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreateChannelRejectsInvalidChannelType() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "tenantId" to "00000000-0000-0000-0000-000000000000",
                    "name" to "alerts channel",
                    "channelType" to "invalid"
                )
            )
        .`when`()
            .post("/api/v1/alerts/channels")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid channel type"))
    }

    @Test
    fun testListRulesRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", "bad-tenant")
        .`when`()
            .get("/api/v1/alerts/rules")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testCreateRuleAcceptsTenantQueryAndObjectCondition() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = resolveTenantId(token)

        val channelId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Query Tenant Channel",
                    "channelType" to "WEBHOOK",
                    "config" to mapOf("url" to "https://example.com/hook")
                )
            )
        .`when`()
            .post("/api/v1/alerts/channels?tenantId=$tenantId")
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
                    "name" to "Temp Rule",
                    "channelId" to channelId,
                    "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 100)
                )
            )
        .`when`()
            .post("/api/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
    }

    @Test
    fun testCreateRuleStoresSeverityAndMessageAsIndependentFields() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val ruleId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Severity Rule",
                "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 100),
                "severity" to "CRITICAL",
                "message" to "Temperature exceeded safe threshold"
            ))
        .`when`()
            .post("/api/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .body("severity", equalTo("CRITICAL"))
            .body("message", equalTo("Temperature exceeded safe threshold"))
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(200)
            .body("items.find { it.id == '$ruleId' }.severity", equalTo("CRITICAL"))
            .body("items.find { it.id == '$ruleId' }.message", equalTo("Temperature exceeded safe threshold"))

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("severity" to "INFO", "message" to "Back to normal"))
        .`when`()
            .patch("/api/v1/alerts/rules/$ruleId")
        .then()
            .statusCode(200)
            .body("severity", equalTo("INFO"))
            .body("message", equalTo("Back to normal"))
    }

    @Test
    fun testCreateRuleRejectsInvalidSeverity() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Bad Severity Rule",
                "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 100),
                "severity" to "SUPER_URGENT"
            ))
        .`when`()
            .post("/api/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid severity"))
    }

    @Test
    fun `alert channel mutation writes a redacted audit record`() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val channelId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Audited channel",
                "channelType" to "WEBHOOK",
                "config" to mapOf("url" to "https://example.com/hook", "secretRefs" to mapOf("authorization" to mapOf("id" to UUID.randomUUID().toString(), "version" to 1)))
            ))
        .`when`().post("/api/v1/alerts/channels?tenantId=$tenantId")
        .then().statusCode(201).extract().jsonPath().getString("id")

        val audit = QuarkusTransaction.requiringNew().call {
            em.createQuery(
                "SELECT e FROM AuditEvent e WHERE e.action = :action AND e.resourceId = :resourceId",
                AuditEvent::class.java
            ).setParameter("action", "alert.channel.created")
                .setParameter("resourceId", channelId)
                .singleResult
        }
        assertEquals("alert_channel", audit.resourceType)
        assertFalse(audit.metadata.orEmpty().contains("audit-marker-secret"))
    }

    @Test
    fun `alert channel rejects inline credentials in favor of secret references`() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Inline credential", "channelType" to "WEBHOOK", "config" to mapOf("url" to "https://example.com/hook", "authorization" to "must-not-persist")))
        .`when`().post("/api/v1/alerts/channels?tenantId=$tenantId")
        .then().statusCode(400)
            .body("error", equalTo("Channel credentials must use secretRefs"))
    }

    @Test
    fun testUpdateChannelSupportsNameAndIsActive() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val channelId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Editable Channel", "channelType" to "WEBHOOK", "config" to mapOf("url" to "https://a")))
        .`when`()
            .post("/api/v1/alerts/channels?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Updated Channel", "isActive" to false))
        .`when`()
            .patch("/api/v1/alerts/channels/$channelId")
        .then()
            .statusCode(200)
            .body("name", equalTo("Updated Channel"))
            .body("isActive", equalTo(false))
    }

    @Test
    fun testUpdateRuleSupportsConditionAndEnabled() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val ruleId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Editable Rule",
                    "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 90)
                )
            )
        .`when`()
            .post("/api/v1/alerts/rules?tenantId=$tenantId")
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
                    "enabled" to false,
                    "condition" to mapOf("field" to "humidity", "operator" to ">", "value" to 80)
                )
            )
        .`when`()
            .patch("/api/v1/alerts/rules/$ruleId")
        .then()
            .statusCode(200)
            .body("enabled", equalTo(false))
            .body("condition.field", equalTo("humidity"))
    }

    @Test
    fun testRulePreviewEvaluatesWithoutDispatching() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val ruleId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Preview rule", "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 90)))
        .`when`().post("/api/v1/alerts/rules?tenantId=$tenantId")
        .then().statusCode(201).extract().jsonPath().getString("id")

        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body(mapOf("payload" to mapOf("temperature" to 95)))
        .`when`().post("/api/v1/alerts/rules/$ruleId/test")
        .then().statusCode(200)
            .body("matched", equalTo(true))
            .body("wouldDispatch", equalTo(false))
    }

    @Test
    fun `rule actions support multiple tenant channels and safe preview`() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        fun createChannel(name: String): String = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body(mapOf("name" to name, "channelType" to "WEBHOOK", "config" to mapOf("url" to "https://example.com/$name")))
        .`when`().post("/api/v1/alerts/channels?tenantId=$tenantId")
        .then().statusCode(201).extract().jsonPath().getString("id")
        val first = createChannel("Action one")
        val second = createChannel("Action two")

        val ruleId = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body(mapOf(
                "name" to "Multiple actions",
                "channelIds" to listOf(first, second),
                "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 90)
            ))
        .`when`().post("/api/v1/alerts/rules?tenantId=$tenantId")
        .then().statusCode(201)
            .body("actions.size()", equalTo(2))
            .body("channelIds", org.hamcrest.Matchers.containsInAnyOrder(first, second))
            .extract().jsonPath().getString("id")

        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body(mapOf("payload" to mapOf("temperature" to 95)))
        .`when`().post("/api/v1/alerts/rules/$ruleId/test")
        .then().statusCode(200)
            .body("matched", equalTo(true))
            .body("wouldDispatch", equalTo(false))
            .body("actions.size()", equalTo(2))
            .body("actions.channelId", org.hamcrest.Matchers.containsInAnyOrder(first, second))
    }

    @Test
    fun testCreateRuleRejectsInvalidConditionFormat() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Bad Rule",
                    "condition" to mapOf("operator" to ">", "value" to 10)
                )
            )
        .`when`()
            .post("/api/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid condition format"))
    }

    @Test
    fun testListChannelsRejectsUnauthorizedTenantScope() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(adminToken)
        val outsiderToken = createNonMemberToken()

        given()
            .header("Authorization", "Bearer $outsiderToken")
            .queryParam("tenantId", tenantId)
        .`when`()
            .get("/api/v1/alerts/channels")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testCreateChannelRejectsUnauthorizedTenantScope() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(adminToken)
        val outsiderToken = createNonMemberToken()

        given()
            .header("Authorization", "Bearer $outsiderToken")
            .contentType("application/json")
            .body(mapOf("name" to "Unauthorized Channel", "channelType" to "WEBHOOK", "config" to mapOf("url" to "https://example.com/hook")))
        .`when`()
            .post("/api/v1/alerts/channels?tenantId=$tenantId")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

}
