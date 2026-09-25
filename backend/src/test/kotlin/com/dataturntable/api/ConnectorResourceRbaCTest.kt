package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
// BUG: Tests omit the connector API base path
// Details: ConnectorResource is mapped under /api/internal/v1/connectors, but these requests use /connectors. They currently receive 404 before authentication is evaluated, so the 403 assertions do not test RBAC.
class ConnectorResourceRbaCTest {

    @Test
    fun listConnectorsWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .`when`().get("/api/internal/v1/connectors?tenantId=00000000-0000-0000-0000-000000000001")
            .then()
            .statusCode(401)
    }

    @Test
    fun createConnectorWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("tenantId" to "00000000-0000-0000-0000-000000000001", "name" to "c1", "connectorType" to "MQTT", "direction" to "INPUT"))
            .`when`().post("/api/internal/v1/connectors")
            .then()
            .statusCode(401)
    }
}
