package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
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
