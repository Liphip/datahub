package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test

@QuarkusTest
class SecretResourceTest {
    @Test
    fun `secret management never returns plaintext values`() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = given().header("Authorization", "Bearer $token")
        .`when`().get("/api/internal/v1/tenants")
        .then().statusCode(200).extract().jsonPath().getString("[0].id")

        val id = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("name" to "test.service-token", "value" to "write-only-value"))
        .`when`().post("/api/internal/v1/secrets?tenantId=$tenantId")
        .then().statusCode(201)
            .body("value", org.hamcrest.Matchers.nullValue())
            .extract().jsonPath().getString("id")

        given().header("Authorization", "Bearer $token")
        .`when`().get("/api/internal/v1/secrets?tenantId=$tenantId")
        .then().statusCode(200)
            .body("find { it.id == '$id' }.name", org.hamcrest.Matchers.equalTo("test.service-token"))
            .body("find { it.id == '$id' }.value", org.hamcrest.Matchers.nullValue())
    }
}
