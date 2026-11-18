package com.dataturntable.api

import com.dataturntable.auth.LocalLoginPolicy
import com.dataturntable.service.AuthService
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.mockito.InjectSpy
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.Mockito.`when`
import java.util.UUID

@QuarkusTest
class LocalLoginPolicyResourceTest {
    @InjectSpy lateinit var policy: LocalLoginPolicy
    @Inject lateinit var auth: AuthService

    @Test fun passwordRestrictionDoesNotBlockALinkedExternalLogin() {
        val token = TestAuthHelpers.createAdminToken()
        val id = given().header("Authorization", "Bearer $token").get("/api/internal/v1/auth/me")
            .then().statusCode(200).extract().jsonPath().getString("id")
        val uuid = UUID.fromString(id)
        `when`(policy.allows(uuid)).thenReturn(false)
        given().contentType(ContentType.JSON)
            .body(mapOf("email" to "admin@example.com", "password" to "AdminPass!123"))
            .post("/api/internal/v1/auth/login").then().statusCode(401)
        // Only the verified, linked OIDC handoff calls this service method in the application.
        assertEquals(id, auth.loginExternal(uuid).user.id)
        `when`(policy.allows(uuid)).thenReturn(true)
        given().contentType(ContentType.JSON)
            .body(mapOf("email" to "admin@example.com", "password" to "AdminPass!123"))
            .post("/api/internal/v1/auth/login").then().statusCode(200)
    }
}
