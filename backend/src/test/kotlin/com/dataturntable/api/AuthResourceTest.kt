package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertHistory
import com.dataturntable.alert.AlertRule
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

import java.security.MessageDigest
import java.util.Base64

@QuarkusTest
class AuthResourceTest {
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    @BeforeEach
    @Transactional
    fun cleanup() {
        em.createQuery("DELETE FROM AlertHistory").executeUpdate()
        em.createQuery("DELETE FROM AlertRule").executeUpdate()
        em.createQuery("DELETE FROM AlertChannel").executeUpdate()
        em.createQuery("DELETE FROM Membership").executeUpdate()
        em.createQuery("DELETE FROM User").executeUpdate()
        em.createQuery("DELETE FROM Role").executeUpdate()
        em.createQuery("DELETE FROM Tenant").executeUpdate()
    }

    @AfterEach
    @Transactional
    fun cleanupAfter() {
        cleanup()
    }
    
    @Test
    fun testSetupEndpoint() {
        val requestBody = """
            {
                "email": "admin@example.com"
            }
        """.trimIndent()
        
        given()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .statusCode(201)
            .body("email", equalTo("admin@example.com"))
            .body("password", notNullValue())
            .body("user.email", equalTo("admin@example.com"))
            .body("user.superadmin", equalTo(true))
    }
    
    @Test
    fun testSetupFailsWhenAlreadyConfigured() {
        // First setup to configure the system
        val firstRequestBody = """
            {
                "email": "admin@example.com"
            }
        """.trimIndent()
        
        given()
            .contentType(ContentType.JSON)
            .body(firstRequestBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .statusCode(201)
        
        // Second setup should fail
        val secondRequestBody = """
            {
                "email": "admin2@example.com"
            }
        """.trimIndent()
        
        given()
            .contentType(ContentType.JSON)
            .body(secondRequestBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .statusCode(409)
            .body("error", notNullValue())
    }

    @Test
    fun testRegisterEndpointCreatesAndAuthenticatesUser() {
        val registerBody = """
            {
                "tenantName": "Tenant-${'$'}{java.util.UUID.randomUUID()}",
                "email": "register@example.com",
                "passwordHash": "${hashPassword("Password123!")}"
            }
        """.trimIndent()

        given()
            .contentType(ContentType.JSON)
            .body(registerBody)
        .`when`()
            .post("/api/internal/v1/auth/register")
        .then()
            .statusCode(201)
            .body("token", notNullValue())
            .body("user.email", equalTo("register@example.com"))
    }

    @Test
    fun testLoginAcceptsHexEncodedSha256Hash() {
        val setupBody = """
            {
                "email": "adminhex@example.com"
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
        val passwordHash = hashPasswordHex(password)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "adminhex@example.com", "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
    }
    
    @Test
    fun testLoginEndpoint() {
        val setupBody = """
            {
                "email": "admin2@example.com"
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
        // Backend stores BCrypt(SHA-256(password))
        val passwordHash = hashPassword(password)
        
        val loginBody = """
            {
                "email": "admin2@example.com",
                "passwordHash": "$passwordHash"
            }
        """.trimIndent()
        
        given()
            .contentType(ContentType.JSON)
            .body(loginBody)
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("user.email", equalTo("admin2@example.com"))
    }

    @Test
    fun testSetupAcceptsCustomPasswordForLogin() {
        val customPassword = "MyCustomPass!123"
        val setupBody = mapOf(
            "email" to "custompass@example.com",
            "password" to customPassword
        )

        given()
            .contentType(ContentType.JSON)
            .body(setupBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .statusCode(201)
            .body("email", equalTo("custompass@example.com"))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "custompass@example.com", "passwordHash" to hashPassword(customPassword)))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
    }
    
    @Test
    fun testLoginWithInvalidCredentials() {
        val loginBody = """
            {
                "email": "admin2@example.com",
                "passwordHash": "invalidhash"
            }
        """.trimIndent()
        
        given()
            .contentType(ContentType.JSON)
            .body(loginBody)
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(401)
            .body("error", equalTo("Invalid credentials"))
    }
    
    @Test
    fun testMeEndpointWithValidToken() {
        val setupBody = """
            {
                "email": "admin3@example.com"
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
        // Backend stores BCrypt(SHA-256(password))
        val passwordHash = hashPassword(password)
        
        val loginBody = """
            {
                "email": "admin3@example.com",
                "passwordHash": "$passwordHash"
            }
        """.trimIndent()
        
        val token = given()
            .contentType(ContentType.JSON)
            .body(loginBody)
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")
        
        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/auth/me")
        .then()
            .statusCode(200)
            .body("email", equalTo("admin3@example.com"))
    }
    
    @Test
    fun testMeEndpointWithoutToken() {
        given()
        .`when`()
            .get("/api/internal/v1/auth/me")
        .then()
            .statusCode(401)
    }

    @Test
    fun testSwitchTenantUpdatesJwtContext() {
        val setupResponse = given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "switch@example.com"))
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val password = setupResponse.getString("password")
        val rootTenantId = setupResponse.getString("user.tenantId")
        val token = given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "switch@example.com", "passwordHash" to hashPassword(password)))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")

        val tenantId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Secondary Tenant", "parentId" to rootTenantId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val switchedToken = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("tenantId" to tenantId))
        .`when`()
            .post("/api/internal/v1/auth/switch-tenant")
        .then()
            .statusCode(200)
            .body("user.tenantId", equalTo(tenantId))
            .extract()
            .body()
            .jsonPath()
            .getString("token")

        given()
            .header("Authorization", "Bearer $switchedToken")
        .`when`()
            .get("/api/internal/v1/auth/me")
        .then()
            .statusCode(200)
            .body("tenantId", equalTo(tenantId))
    }

    @Test
    fun testListTenantContextsRequiresAuth() {
        given()
        .`when`()
            .get("/api/internal/v1/auth/tenants")
        .then()
            .statusCode(401)
    }
    
    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }

    private fun hashPasswordHex(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
