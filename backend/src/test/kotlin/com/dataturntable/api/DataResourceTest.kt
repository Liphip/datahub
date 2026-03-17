package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class DataResourceTest {

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

    @Test
    fun testQueryRejectsInvalidTableName() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "bad-table;drop")
        .`when`()
            .get("/api/internal/v1/data/$tenantId/query")
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
            .get("/api/internal/v1/data/$tenantId/query")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid start timestamp"))
    }
}
