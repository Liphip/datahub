package com.dataturntable.test

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.security.MessageDigest
import java.util.Base64

object TestAuthHelpers {
    fun createAdminToken(): String {
        val setupBody = """
            {
                "email": "admin@example.com"
            }
        """.trimIndent()

        val setupResponse = given()
            .contentType(ContentType.JSON)
            .body(setupBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val password = setupResponse.getString("password")
        val passwordHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(password.toByteArray()))

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "admin@example.com", "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")
    }
}
