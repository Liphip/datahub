package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.*

@QuarkusTest
class MembershipResourceTest {

    @Test
    fun testCreateAndListMembershipsWithAdminAuth() {
        val token = TestAuthHelpers.createAdminToken()

        // Get existing tenants
        val tenants = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()

        val tenantId = tenants.getList<Map<String, Any>>("").first()["id"].toString()

        // Create a role for the tenant
        val roleResp = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Test Role", "abilities" to listOf("manage:tenant")))
        .`when`()
            .post("/api/internal/v1/roles?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val roleId = roleResp.getString("id")

        // Create a new user to be a member
        val password = "password123"
        val passwordHash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray()))

        val userResp = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "member@example.com", "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth-users")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val userId = userResp.getString("id")

        // Create membership
        val createResp = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("authUserId" to userId, "roleId" to roleId))
        .`when`()
            .post("/api/internal/v1/tenants/$tenantId/memberships")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract()
            .body()
            .jsonPath()

        val membershipId = createResp.getString("id")

        // List memberships and assert presence
        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("abilities" to listOf("module:read")))
        .`when`()
            .patch("/api/internal/v1/tenants/$tenantId/memberships/$membershipId")
        .then()
            .statusCode(200)
            .body("id", equalTo(membershipId))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/$tenantId/memberships")
        .then()
            .statusCode(200)
            .body("size()", greaterThan(0))
            .body("find { it.id == '${membershipId}' }", notNullValue())
    }
}
