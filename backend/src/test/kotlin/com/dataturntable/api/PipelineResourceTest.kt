package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class PipelineResourceTest {
    
    @Test
    fun testListPipelinesRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/pipelines")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetPipelineNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/pipelines/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
}
