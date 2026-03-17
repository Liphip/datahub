package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertHistory
import com.dataturntable.alert.AlertRule
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mindrot.jbcrypt.BCrypt

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class TenantResourceTest {
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    @BeforeEach
    @Transactional
    fun cleanup() {
        em.createQuery("DELETE FROM Membership").executeUpdate()
        em.createQuery("DELETE FROM Role").executeUpdate()
        em.createQuery("DELETE FROM User").executeUpdate()
        em.createQuery("DELETE FROM AlertChannel").executeUpdate()
        em.createQuery("DELETE FROM AlertRule").executeUpdate()
        em.createQuery("DELETE FROM AlertHistory").executeUpdate()
        em.createQuery("DELETE FROM Tenant").executeUpdate()
    }

    fun createNonAdminToken(): String {
        val email = "viewer@example.com"
        val passwordHash = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("ViewerPass!123".toByteArray())
        )

        QuarkusTransaction.requiringNew().run {
            val user = User().apply {
                this.email = email
                this.passwordHash = BCrypt.hashpw(passwordHash, BCrypt.gensalt())
                this.isActive = true
                this.createdAt = java.time.Instant.now()
                this.updatedAt = java.time.Instant.now()
            }
            em.persist(user)
            em.flush()
        }

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to email, "passwordHash" to passwordHash))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")
    }
    
    @Test
    fun testListTenantsRequiresAuth() {
        given()
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetTenantNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/tenants/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }

    @Test
    fun testListTenantsRecursiveIncludesChildren() {
        val token = TestAuthHelpers.createAdminToken()

        val rootId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Child A1", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("recursive", "true")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .body("find { it.id == '${rootId}' }.children.size()", equalTo(1))
            .body("find { it.id == '${rootId}' }.children[0].name", equalTo("Child A1"))
            .body("find { it.id == '${rootId}' }.createdAt", notNullValue())
    }

    @Test
    fun testTenantChildrenRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/not-a-uuid/children")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenant id"))
    }

    @Test
    fun testListTenantsRecursiveSupportsQueryFilter() {
        val token = TestAuthHelpers.createAdminToken()

        val rootId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Alpha Child", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Beta Child", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("recursive", "true")
            .queryParam("query", "Alpha")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .body("toString()", containsString("Alpha Child"))
            .body("toString()", not(containsString("Beta Child")))
    }

    @Test
    fun testListTenantsRejectsNonSuperadmin() {
        val token = createNonAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testGetTenantRejectsNonSuperadmin() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val tenantId = given()
            .header("Authorization", "Bearer $adminToken")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val token = createNonAdminToken()
        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/$tenantId")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testChildrenRejectsNonSuperadmin() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val tenantId = given()
            .header("Authorization", "Bearer $adminToken")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val token = createNonAdminToken()
        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/$tenantId/children")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }
}
