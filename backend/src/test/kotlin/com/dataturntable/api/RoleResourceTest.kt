package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class RoleResourceTest {
    
    @Test
    fun testListRolesRequiresAuth() {
        given()
        .`when`()
            .get("/api/internal/v1/roles")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetRoleNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/roles/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testCreateRoleRequiresAuth() {
        given()
        .contentType("application/json")
        .body(mapOf(
            "name" to "Test Role",
            "abilities" to listOf("module:read", "pipeline:write")
        ))
        .`when`()
            .post("/api/internal/v1/roles")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testUpdateRoleRequiresAuth() {
        given()
        .contentType("application/json")
        .body(mapOf(
            "name" to "Updated Role"
        ))
        .`when`()
            .patch("/api/internal/v1/roles/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testDeleteRoleRequiresAuth() {
        given()
        .`when`()
            .delete("/api/internal/v1/roles/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }

    @Test
    fun testListRolesRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", "not-a-uuid")
        .`when`()
            .get("/api/internal/v1/roles")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testGetRoleRejectsInvalidRoleId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/roles/not-a-uuid")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid role id"))
    }
}
