package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.hamcrest.CoreMatchers
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class UserResourceUnitTest {

    @Test
    fun testListWithoutAuthIsUnauthorized() {
        RestAssured.given()
            .`when`().get("/api/internal/v1/auth-users")
            .then()
            .statusCode(401)
    }

    @Test
    fun testCreateMissingPassword() {
        val token = TestAuthHelpers.createAdminToken()

        RestAssured.given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("email" to "new-user-${UUID.randomUUID()}@example.com"))
            .`when`().post("/api/internal/v1/auth-users")
            .then()
            .statusCode(400)
            .body("error", CoreMatchers.equalTo("Password is required"))
    }
}
