package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.hamcrest.CoreMatchers
import org.junit.jupiter.api.Test

@QuarkusTest
class UserResourceUnitTest {

    @Test
    fun testListRequiresTenant() {
        RestAssured.given()
            .`when`().get("/users")
            .then()
            .statusCode(400)
    }

    @Test
    fun testCreateMissingEmail() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("tenantId" to "00000000-0000-0000-0000-000000000001"))
            .`when`().post("/users")
            .then()
            .statusCode(400)
            .body("error", CoreMatchers.equalTo("Email is required"))
    }
}
