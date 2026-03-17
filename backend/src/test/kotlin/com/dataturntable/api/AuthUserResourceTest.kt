package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class AuthUserResourceTest {

    @Test
    fun testListAuthUsersRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/auth-users")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreateAuthUserAcceptsPasswordAlias() {
        val token = TestAuthHelpers.createAdminToken()
        val email = "auth-user-${UUID.randomUUID()}@example.com"
        val passwordHash = "dGVzdC1zaGEyNTYtaGFzaA=="

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "email" to email,
                    "password" to passwordHash
                )
            )
        .`when`()
            .post("/api/internal/v1/auth-users")
        .then()
            .statusCode(201)
            .body("email", equalTo(email))

        given()
            .contentType("application/json")
            .body(mapOf("email" to email, "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
    }

    @Test
    fun testListAuthUsersRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", "bad-tenant")
        .`when`()
            .get("/api/internal/v1/auth-users")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testUpdateAuthUserCanToggleActiveStatus() {
        val token = TestAuthHelpers.createAdminToken()
        val email = "auth-user-active-${UUID.randomUUID()}@example.com"
        val passwordHash = "dGVzdC1zaGEyNTYtaGFzaA=="

        val userId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("email" to email, "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth-users")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("isActive" to false))
        .`when`()
            .patch("/api/internal/v1/auth-users/$userId")
        .then()
            .statusCode(200)

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/auth-users/$userId")
        .then()
            .statusCode(200)
            .body("isActive", equalTo(false))
    }

    @Test
    fun testGetAuthUserRejectsInvalidUserId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/auth-users/not-a-uuid")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid user id"))
    }
}
