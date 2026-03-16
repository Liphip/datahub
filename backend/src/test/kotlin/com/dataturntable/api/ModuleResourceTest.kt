package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*
import java.util.UUID

@QuarkusTest
class ModuleResourceTest {
    
    @Test
    fun testListModulesRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/modules")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetModuleNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/modules/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreateModuleRejectsInvalidModuleType() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Module ${UUID.randomUUID()}",
                    "moduleType" to "invalid_type",
                    "tenantId" to "00000000-0000-0000-0000-000000000000"
                )
            )
        .`when`()
            .post("/api/internal/v1/modules")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid module type"))
    }

    @Test
    fun testListModulesRejectsInvalidTypeFilter() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = given()
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

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", tenantId)
            .queryParam("type", "not-a-type")
        .`when`()
            .get("/api/internal/v1/modules")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid module type"))
    }
}
