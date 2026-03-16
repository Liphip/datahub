package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class ConnectorResourceTest {

    @Test
    fun testListConnectorsRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/connectors")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetConnectorNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/connectors/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testCreateConnectorRequiresAuth() {
        given()
        .contentType("application/json")
        .body(mapOf(
            "name" to "Test Connector",
            "connectorType" to "MQTT",
            "direction" to "INPUT"
        ))
        .`when`()
            .post("/api/internal/v1/connectors?tenantId=00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testCreateConnectorStoresJsonConfig() {
        val token = TestAuthHelpers.createAdminToken()

        val config = mapOf("host" to "test.local", "port" to 1883)

        val response = given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf(
                "name" to "Test Connector",
                "connectorType" to "MQTT",
                "direction" to "INPUT",
                "config" to config
            ))
        .`when`()
            .post("/api/internal/v1/connectors")
        .then()
            .statusCode(201)
            .body("name", equalTo("Test Connector"))
            .extract()
            .body()
            .jsonPath()

        val id = response.getString("id")

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/connectors/$id")
        .then()
            .statusCode(200)
            .body("config.host", equalTo("test.local"))
            .body("config.port", equalTo(1883))
    }
    
    @Test
    fun testDeleteConnectorRequiresAuth() {
        given()
        .`when`()
            .delete("/api/internal/v1/connectors/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
}
