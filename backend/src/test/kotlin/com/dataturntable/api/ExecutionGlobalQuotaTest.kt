package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

class SmallGlobalExecutionQuotaProfile : QuarkusTestProfile {
    // A custom profile replaces Quarkus's implicit test profile. Keep the H2/Flyway-off
    // configuration while narrowing only the execution capacity limits for this class.
    override fun getConfigProfile(): String = "test"

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "dataturntable.execution.max-concurrent-global" to "2",
        "dataturntable.execution.max-concurrent-per-tenant" to "10"
    )
}

@QuarkusTest
@TestProfile(SmallGlobalExecutionQuotaProfile::class)
class ExecutionGlobalQuotaTest {
    @Test
    fun `global execution allocation spans tenants`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = given().header("Authorization", "Bearer $adminToken")
            .`when`().get("/api/internal/v1/tenants")
            .then().statusCode(200).extract().jsonPath().getString("[0].id")
        val tenantA = createTenant(adminToken, rootId, "Global quota A")
        val tenantB = createTenant(adminToken, rootId, "Global quota B")
        val tenantC = createTenant(adminToken, rootId, "Global quota C")
        val versionA = createVersion(adminToken, tenantA)
        val versionB = createVersion(adminToken, tenantB)
        val versionC = createVersion(adminToken, tenantC)

        submit(adminToken, tenantA, versionA).then().statusCode(202)
        submit(adminToken, tenantB, versionB).then().statusCode(202)
        submit(adminToken, tenantC, versionC).then().statusCode(429)
            .body("error", equalTo("Execution quota exceeded"))
            .body("details.scope", equalTo("global"))
            .body("details.limit", equalTo(2))
            .body("details.active", equalTo(2))
    }

    private fun createTenant(token: String, rootId: String, name: String): String = given()
        .header("Authorization", "Bearer $token").contentType(ContentType.JSON)
        .body(mapOf("name" to "$name ${UUID.randomUUID()}", "parentId" to rootId))
    .`when`().post("/api/internal/v1/tenants")
    .then().statusCode(201).extract().jsonPath().getString("id")

    private fun createVersion(token: String, tenantId: String): String {
        val moduleId = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("name" to "Global quota module ${UUID.randomUUID()}", "moduleType" to "PROCESSING", "tenantId" to tenantId))
        .`when`().post("/api/internal/v1/modules")
        .then().statusCode(201).extract().jsonPath().getString("id")
        return given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("code" to "def process(data):\n    return data"))
        .`when`().post("/api/internal/v1/modules/$moduleId/versions")
        .then().statusCode(201).extract().jsonPath().getString("id")
    }

    private fun submit(token: String, tenantId: String, versionId: String) = given()
        .header("Authorization", "Bearer $token").contentType(ContentType.JSON)
        .body(mapOf("tenantId" to tenantId, "moduleVersionId" to versionId))
    .`when`().post("/api/internal/v1/executions")
}
