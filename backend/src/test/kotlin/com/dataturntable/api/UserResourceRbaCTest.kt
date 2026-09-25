package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class UserResourceRbaCTest {

    @Test
    fun createUserWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("email" to "a@b.com", "password" to "x"))
            .`when`().post("/api/internal/v1/auth-users")
            .then()
            .statusCode(401)
    }

    @Test
    fun listUsersWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .`when`().get("/api/internal/v1/auth-users?tenantId=00000000-0000-0000-0000-000000000001")
            .then()
            .statusCode(401)
    }

    @Test
    fun createUserMissingEmailReturnsBadRequestWithAuth() {
        val token = TestAuthHelpers.createAdminToken()

        RestAssured.given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("password" to "x"))
            .`when`().post("/api/internal/v1/auth-users")
            .then()
            .statusCode(400)
            .body("error", equalTo("Email is required"))
    }
}
