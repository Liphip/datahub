package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
class UserResourceRbaCTest {

    @Test
    fun createUserWithoutAuthReturnsForbidden() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("tenantId" to "00000000-0000-0000-0000-000000000001", "email" to "a@b.com", "passwordHash" to "x"))
            .`when`().post("/users")
            .then()
            .statusCode(403)
    }

    @Test
    fun listUsersWithoutAuthReturnsForbidden() {
        RestAssured.given()
            .`when`().get("/users?tenantId=00000000-0000-0000-0000-000000000001")
            .then()
            .statusCode(403)
    }
}
