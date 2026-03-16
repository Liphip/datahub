package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class DataResourceTest {

    @Test
    fun testListTablesRequiresAuth() {
        given()
        .`when`()
            .get("/api/internal/v1/data/00000000-0000-0000-0000-000000000000/tables")
        .then()
            .statusCode(401)
    }

    @Test
    fun testListTablesRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/data/not-a-uuid/tables")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }
}
