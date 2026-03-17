package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

@QuarkusTest
class AlertResourceTest {

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
    fun testListChannelsRequiresAuth() {
        given()
            .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/alerts/channels")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreateChannelRejectsInvalidChannelType() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "tenantId" to "00000000-0000-0000-0000-000000000000",
                    "name" to "alerts channel",
                    "channelType" to "invalid"
                )
            )
        .`when`()
            .post("/api/internal/v1/alerts/channels")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid channel type"))
    }

    @Test
    fun testListRulesRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", "bad-tenant")
        .`when`()
            .get("/api/internal/v1/alerts/rules")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testCreateRuleAcceptsTenantQueryAndObjectCondition() {
        val token = TestAuthHelpers.createAdminToken()

        val tenantId = resolveTenantId(token)

        val channelId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Query Tenant Channel",
                    "channelType" to "WEBHOOK",
                    "config" to mapOf("url" to "https://example.com/hook")
                )
            )
        .`when`()
            .post("/api/internal/v1/alerts/channels?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Temp Rule",
                    "channelId" to channelId,
                    "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 100)
                )
            )
        .`when`()
            .post("/api/internal/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
    }

    @Test
    fun testUpdateChannelSupportsNameAndIsActive() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val channelId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Editable Channel", "channelType" to "WEBHOOK", "config" to mapOf("url" to "https://a")))
        .`when`()
            .post("/api/internal/v1/alerts/channels?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("name" to "Updated Channel", "isActive" to false))
        .`when`()
            .patch("/api/internal/v1/alerts/channels/$channelId")
        .then()
            .statusCode(200)
            .body("name", equalTo("Updated Channel"))
            .body("isActive", equalTo(false))
    }

    @Test
    fun testUpdateRuleSupportsConditionAndEnabled() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        val ruleId = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Editable Rule",
                    "condition" to mapOf("field" to "temperature", "operator" to ">", "value" to 90)
                )
            )
        .`when`()
            .post("/api/internal/v1/alerts/rules?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "enabled" to false,
                    "condition" to mapOf("field" to "humidity", "operator" to ">", "value" to 80)
                )
            )
        .`when`()
            .patch("/api/internal/v1/alerts/rules/$ruleId")
        .then()
            .statusCode(200)
            .body("enabled", equalTo(false))
            .body("condition.field", equalTo("humidity"))
    }
}
