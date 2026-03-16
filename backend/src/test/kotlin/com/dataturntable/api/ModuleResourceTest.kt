package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

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
}
