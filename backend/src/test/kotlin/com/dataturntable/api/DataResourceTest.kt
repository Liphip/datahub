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
import org.junit.jupiter.api.Test
import io.quarkus.elytron.security.common.BcryptUtil
import java.time.Instant
import java.util.UUID

@QuarkusTest
class DataResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    private fun createNonMemberToken(): String {
        val email = "data-outsider-${java.util.UUID.randomUUID()}@example.com"
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
    fun testListTablesRequiresAuth() {
        given()
        .`when`()
            .get("/api/v1/data/00000000-0000-0000-0000-000000000000/tables")
        .then()
            .statusCode(401)
    }

    @Test
    fun testListTablesRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/v1/data/not-a-uuid/tables")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testQueryRejectsInvalidTableName() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "bad-table;drop")
        .`when`()
            .get("/api/v1/data/$tenantId/query")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid table name"))
    }

    @Test
    fun testQueryRejectsInvalidStartTimestamp() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "measurements")
            .queryParam("start", "not-a-timestamp")
        .`when`()
            .get("/api/v1/data/$tenantId/query")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid start timestamp"))
    }

    @Test
    fun testListTablesRejectsUnauthorizedTenantScope() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val outsiderToken = createNonMemberToken()

        given()
            .header("Authorization", "Bearer $outsiderToken")
        .`when`()
            .get("/api/v1/data/$tenantId/tables")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testSummaryRejectsInvalidNumericField() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "measurements")
            .queryParam("numericField", "value;drop")
        .`when`()
            .get("/api/v1/data/$tenantId/summary")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid numericField"))
    }

    @Test
    fun testRegisterInsertAndQueryDataThroughPublicApi() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val tableName = "sensor_${UUID.randomUUID().toString().replace("-", "_").take(12)}"
        val timestamp = Instant.parse("2026-03-23T12:00:00Z").toString()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", tableName)
            .contentType(ContentType.JSON)
            .body(
                listOf(
                    mapOf(
                        "time" to timestamp,
                        "device_id" to "device-a",
                        "value" to 42.5,
                        "tags" to mapOf("location" to "lab")
                    )
                )
            )
        .`when`()
            .post("/api/v1/data/$tenantId/insert")
        .then()
            .statusCode(200)
            .body("inserted", equalTo(1))
            .body("table", equalTo(tableName))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", tableName)
            .queryParam("limit", 10)
        .`when`()
            .get("/api/v1/data/$tenantId/query")
        .then()
            .statusCode(200)
            .body("count", equalTo(1))
            .body("data[0].device_id", equalTo("device-a"))
    }

}
