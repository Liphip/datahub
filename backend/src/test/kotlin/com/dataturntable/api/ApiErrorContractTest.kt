package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test

@QuarkusTest
class ApiErrorContractTest {
    @Test
    fun `invalid ingest request uses safe machine-readable error envelope`() {
        given()
            .contentType("application/json")
            .body("{}")
            .`when`()
            .post("/api/ingest/v1/ingest/not-a-uuid")
            .then()
            .statusCode(400)
            .header("X-Request-Id", matchesPattern("[0-9a-f-]{36}"))
            .body("code", equalTo("VALIDATION_ERROR"))
            .body("message", equalTo("Invalid pipelineId"))
            .body("requestId", matchesPattern("[0-9a-f-]{36}"))
    }

    @Test
    fun `generated OpenAPI contract is available`() {
        given()
            .`when`()
            .get("/openapi")
            .then()
            .statusCode(200)
    }

    @Test
    fun `every JAX-RS response carries baseline browser-hardening headers`() {
        // /openapi is served by a Vert.x-level route Quarkus registers directly, bypassing the
        // JAX-RS filter chain — exercise a real resource method instead.
        given()
            .contentType("application/json")
            .body("{}")
            .`when`()
            .post("/api/ingest/v1/ingest/not-a-uuid")
            .then()
            .statusCode(400)
            .header("Content-Security-Policy", equalTo("default-src 'none'; frame-ancestors 'none'"))
            .header("X-Frame-Options", equalTo("DENY"))
            .header("X-Content-Type-Options", equalTo("nosniff"))
            .header("Referrer-Policy", equalTo("no-referrer"))
            .header("Strict-Transport-Security", equalTo("max-age=31536000; includeSubDomains"))
    }
}
