package com.dataturntable.api

import com.dataturntable.auth.OidcLoginService
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@QuarkusTest
class ExternalIdentityResourceTest {
    @Inject lateinit var logins: OidcLoginService
    @Inject lateinit var em: EntityManager

    @Test
    fun localDesktopIssuerIsAllowedButLookalikeDomainsAreNot() {
        val token = TestAuthHelpers.createAdminToken()
        val userId = given().header("Authorization", "Bearer $token").get("/api/internal/v1/auth/me")
            .then().statusCode(200).extract().jsonPath().getString("id")
        val path = "/api/internal/v1/users/$userId/identities"
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("issuer" to "http://idp.localhost.example:8180/realm", "subject" to "desktop"))
            .post(path).then().statusCode(400)
        val id = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("issuer" to "http://idp.localhost:8180/realm", "subject" to "desktop"))
            .post(path).then().statusCode(201).extract().jsonPath().getString("id")
        given().header("Authorization", "Bearer $token").delete("$path/$id").then().statusCode(204)
    }

    @Test
    fun linksAreExplicitAuditedAndRevocationInvalidatesPendingAndIssuedLogins() {
        val admin = TestAuthHelpers.createAdminToken()
        val userId = given().header("Authorization", "Bearer $admin").get("/api/internal/v1/auth/me")
            .then().statusCode(200).extract().jsonPath().getString("id")
        val path = "/api/internal/v1/users/$userId/identities"
        val issuer = "https://desktop-id.example/realm"
        given().get(path).then().statusCode(401)
        given().header("Authorization", "Bearer $admin").contentType(ContentType.JSON)
            .body(mapOf("issuer" to "http://insecure.example", "subject" to "subject"))
            .post(path).then().statusCode(400)
        val id = given().header("Authorization", "Bearer $admin").contentType(ContentType.JSON)
            .body(mapOf("issuer" to issuer, "subject" to "subject"))
            .post(path).then().statusCode(201).extract().jsonPath().getString("id")
        try {
            given().header("Authorization", "Bearer $admin").contentType(ContentType.JSON)
                .body(mapOf("issuer" to issuer, "subject" to "subject")).post(path).then().statusCode(409)
            assertThrows(SecurityException::class.java) { logins.begin(issuer, "other-subject") }
            assertThrows(SecurityException::class.java) { logins.begin("https://other-issuer.example", "subject") }
            val expired = logins.begin(issuer, "subject")
            QuarkusTransaction.requiringNew().run {
                em.createQuery("UPDATE OidcLoginTicket t SET t.expiresAt = :past").setParameter("past", Instant.now().minusSeconds(10)).executeUpdate()
            }
            assertThrows(SecurityException::class.java) { logins.exchange(expired.code, expired.browser) }
            val first = logins.begin(issuer, "subject")
            assertThrows(SecurityException::class.java) { logins.exchange(first.code, "wrong-browser") }
            val token = logins.exchange(first.code, first.browser).token
            assertThrows(SecurityException::class.java) { logins.exchange(first.code, first.browser) }
            val pending = logins.begin(issuer, "subject")
            given().header("Authorization", "Bearer $admin").delete("$path/$id").then().statusCode(204)
            assertThrows(SecurityException::class.java) { logins.exchange(pending.code, pending.browser) }
            given().header("Authorization", "Bearer $token").get("/api/internal/v1/auth/me").then().statusCode(401)
        } finally {
            // Unlinking the administrator's own account revokes the fixture's old token.
            val refreshed = TestAuthHelpers.createAdminToken()
            given().header("Authorization", "Bearer $refreshed").delete("$path/$id")
        }
    }
}
