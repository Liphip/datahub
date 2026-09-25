package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import io.quarkus.elytron.security.common.BcryptUtil

@QuarkusTest
// FIXME: Alert endpoint contract is out of date
// Details: AlertResource is intentionally exposed at /api/v1/alerts for the public API, but this test class calls /api/internal/v1/alerts and consequently receives 404 responses. Update these request paths or deliberately add an internal route.
class AlertResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    private fun createNonMemberToken(): String {
        val email = "alerts-outsider-${java.util.UUID.randomUUID()}@example.com"
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
            .getList<Map<String, Any>>("")
            .first()["id"]
            .toString()
    }

    @Test
    fun testListChannelsRequiresAuth() {
        given()
            .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/alerts/channels")
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
            .post("/api/internal/v1/alerts/channels")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid channel type"))
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
            .post("/api/internal/v1/alerts/channels?tenantId=$tenantId")
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
            .patch("/api/internal/v1/alerts/channels/$channelId")
        .then()
            .statusCode(200)
            .body("name", equalTo("Updated Channel"))
            .body("isActive", equalTo(false))
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
            .get("/api/internal/v1/alerts/channels")
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
            .post("/api/internal/v1/alerts/channels?tenantId=$tenantId")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

}
