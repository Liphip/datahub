package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
// BUG: Tests omit the connector API base path
// Details: ConnectorResource is mapped under /api/internal/v1/connectors, but these requests use /connectors. They currently receive 404 before authentication is evaluated, so the 403 assertions do not test RBAC.
class ConnectorResourceRbaCTest {

    @Test
    fun listConnectorsWithoutAuthReturnsForbidden() {
        RestAssured.given()
            .`when`().get("/connectors?tenantId=00000000-0000-0000-0000-000000000001")
            .then()
            .statusCode(403)
    }

    @Test
    fun createConnectorWithoutAuthReturnsForbidden() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("tenantId" to "00000000-0000-0000-0000-000000000001", "name" to "c1", "connectorType" to "MQTT", "direction" to "INPUT"))
            .`when`().post("/connectors")
            .then()
            .statusCode(403)
    }
}
