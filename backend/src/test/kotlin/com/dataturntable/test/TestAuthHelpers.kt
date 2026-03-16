package com.dataturntable.test

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.security.MessageDigest
import java.util.Base64

object TestAuthHelpers {
    fun createAdminToken(): String {
        val password = "AdminPass!123"
        val passwordHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(password.toByteArray()))

        val setupBody = """
            {
                "email": "admin@example.com",
                "passwordHash": "$passwordHash"
            }
        """.trimIndent()

        val setupResponse = given()
            .contentType(ContentType.JSON)
            .body(setupBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .extract()
            .body()
            .jsonPath()

        val setupError = setupResponse.getString("error")
        if (setupError != null && !setupError.contains("already completed", ignoreCase = true)) {
            throw IllegalStateException("Unable to initialize test admin: $setupError")
        }

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
