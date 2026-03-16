package com.dataturntable.api

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
}
