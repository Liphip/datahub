package com.dataturntable.connectors

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression for a production log full of "Connection refresh failed: null": the after-commit
 * actions of the connector and pipeline endpoints read the connection managers through the
 * `@Authenticated` resource, whose intercepted getter throws `UnauthorizedException` on the
 * refresh thread. Saving a pipeline therefore never updated the MQTT subscriptions; they only
 * caught up at the next periodic reconcile.
 */
@QuarkusTest
class AfterCommitRefreshTest {
    @Inject lateinit var afterCommit: AfterCommit

    private val token by lazy { TestAuthHelpers.createAdminToken() }
    private fun api() = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)

    @Test
    fun `saving connectors and pipelines runs their refresh without failing`() {
        // The token's own tenant: the connector update endpoint only accepts that one.
        val tenantId = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        val suffix = UUID.randomUUID().toString().take(8)
        assertTrue(afterCommit.awaitIdle())
        val before = afterCommit.failureCount()

        val mqtt = api().body(mapOf("name" to "mqtt-in-$suffix", "connectorType" to "MQTT", "direction" to "INPUT",
            "config" to mapOf("broker" to "tcp://127.0.0.1:1", "topic" to "#")))
            .post("/api/internal/v1/connectors?tenantId=$tenantId").then().statusCode(201).extract().jsonPath().getString("id")
        val rest = api().body(mapOf("name" to "rest-in-$suffix", "connectorType" to "REST", "direction" to "INPUT",
            "config" to mapOf("baseUrl" to "http://127.0.0.1:1", "path" to "/x")))
            .post("/api/internal/v1/connectors?tenantId=$tenantId").then().statusCode(201).extract().jsonPath().getString("id")
        val out = api().body(mapOf("name" to "mqtt-out-$suffix", "connectorType" to "MQTT", "direction" to "OUTPUT",
            "config" to mapOf("broker" to "tcp://127.0.0.1:1", "topic" to "out")))
            .post("/api/internal/v1/connectors?tenantId=$tenantId").then().statusCode(201).extract().jsonPath().getString("id")
        api().body(mapOf("name" to "mqtt-renamed-$suffix")).patch("/api/internal/v1/connectors/$mqtt").then().statusCode(200)

        val graph = mapOf(
            "nodes" to listOf(
                mapOf("id" to "in", "type" to "IMPORT", "connectorId" to mqtt, "mqttTopic" to "#"),
                mapOf("id" to "out", "type" to "EXPORT", "passThrough" to true, "connectorId" to out),
            ),
            "edges" to listOf(mapOf("source" to "in", "target" to "out")),
        )
        val pipeline = api().body(mapOf("name" to "p1-$suffix", "graphDefinition" to graph))
            .post("/api/internal/v1/pipelines?tenantId=$tenantId").then().log().ifValidationFails().statusCode(201)
            .extract().jsonPath().getString("id")
        api().body(mapOf("isActive" to true)).patch("/api/internal/v1/pipelines/$pipeline").then().statusCode(200)
        api().body(mapOf("isActive" to false)).patch("/api/internal/v1/pipelines/$pipeline").then().statusCode(200)

        assertTrue(afterCommit.awaitIdle(), "after-commit actions did not finish")
        assertEquals(before, afterCommit.failureCount(), "an after-commit refresh failed; see the WARN with its stack trace")
    }
}
