package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class ExecutionResourceQuotaTest {
    @Test
    fun `a tenant cannot reserve more queued executions than its allocation`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = given().header("Authorization", "Bearer $adminToken")
            .`when`().get("/api/internal/v1/tenants")
            .then().statusCode(200).extract().jsonPath().getString("[0].id")
        val tenantId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("name" to "Execution quota ${UUID.randomUUID()}", "parentId" to rootId))
        .`when`().post("/api/internal/v1/tenants")
            .then().statusCode(201).extract().jsonPath().getString("id")
        val moduleId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("name" to "Quota module ${UUID.randomUUID()}", "moduleType" to "PROCESSING", "tenantId" to tenantId))
        .`when`().post("/api/internal/v1/modules")
            .then().statusCode(201).extract().jsonPath().getString("id")
        val versionId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("code" to "def process(data):\n    return data"))
        .`when`().post("/api/internal/v1/modules/$moduleId/versions")
            .then().statusCode(201).extract().jsonPath().getString("id")

        repeat(10) {
            given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
                .body(mapOf("tenantId" to tenantId, "moduleVersionId" to versionId))
            .`when`().post("/api/internal/v1/executions")
            .then().statusCode(202)
        }

        given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("tenantId" to tenantId, "moduleVersionId" to versionId))
        .`when`().post("/api/internal/v1/executions")
        .then().statusCode(429)
            .body("error", org.hamcrest.Matchers.equalTo("Execution quota exceeded"))
            .body("details.scope", org.hamcrest.Matchers.equalTo("tenant"))
            .body("details.limit", org.hamcrest.Matchers.equalTo(10))
            .body("details.active", org.hamcrest.Matchers.equalTo(10))
    }
}
