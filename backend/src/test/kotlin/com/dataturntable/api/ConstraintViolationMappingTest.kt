package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class ConstraintViolationMappingTest {

    @Test
    fun `a duplicate name is a conflict, not a server error`() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = given().header("Authorization", "Bearer $token").get("/api/internal/v1/tenants")
            .then().statusCode(200).extract().jsonPath().getString("[0].id")
        val body = mapOf("name" to "Duplicate ${UUID.randomUUID()}", "connectorType" to "MQTT", "direction" to "INPUT",
            "config" to mapOf("broker" to "mqtt://localhost:1883"))
        repeat(2) { attempt ->
            given().header("Authorization", "Bearer $token").contentType(ContentType.JSON).body(body)
                .post("/api/internal/v1/connectors?tenantId=$tenantId")
                .then().statusCode(if (attempt == 0) 201 else 409)
        }
    }
}
