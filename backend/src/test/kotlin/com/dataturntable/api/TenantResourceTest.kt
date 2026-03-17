package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertHistory
import com.dataturntable.alert.AlertRule
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleVersion
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
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
        try {
            em.createQuery("DELETE FROM ModuleVersion").executeUpdate()
            em.createQuery("DELETE FROM CodeModule").executeUpdate()
            em.createQuery("DELETE FROM Membership").executeUpdate()
            em.createQuery("DELETE FROM Role").executeUpdate()
            em.createQuery("DELETE FROM User").executeUpdate()
            em.createQuery("DELETE FROM AlertChannel").executeUpdate()
            em.createQuery("DELETE FROM AlertRule").executeUpdate()
            em.createQuery("DELETE FROM AlertHistory").executeUpdate()
            em.createQuery("DELETE FROM Tenant").executeUpdate()
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
        }
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

    fun createTenantManagerToken(tenantId: String): String {
        val email = "tenant-admin@example.com"
        val passwordHash = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("TenantPass!123".toByteArray())
        )

        QuarkusTransaction.requiringNew().run {
            val tenantUuid = java.util.UUID.fromString(tenantId)
            val tenant = em.find(Tenant::class.java, tenantUuid)
                ?: throw IllegalStateException("Tenant not found for manager token setup")
            val role = em.createQuery(
                "SELECT r FROM Role r WHERE r.tenant.id = :tenantId AND r.name = :roleName",
                Role::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .setParameter("roleName", "Tenant Admin")
                .resultList
                .firstOrNull()
                ?: throw IllegalStateException("Tenant Admin role missing for tenant")

            val user = User().apply {
                this.email = email
                this.passwordHash = BCrypt.hashpw(passwordHash, BCrypt.gensalt())
                this.isActive = true
                this.createdAt = java.time.Instant.now()
                this.updatedAt = java.time.Instant.now()
            }
            em.persist(user)
            em.flush()

            val membership = Membership().apply {
                this.authUser = user
                this.tenant = tenant
                this.role = role
                this.abilities = "[]"
                this.createdAt = java.time.Instant.now()
            }
            em.persist(membership)
            user.memberships.add(membership)
            em.flush()
        }

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to email, "passwordHash" to passwordHash, "tenantId" to tenantId))
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

    @Test
    fun testTenantManagerCanListScopedTenantsOnly() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = given()
            .header("Authorization", "Bearer $adminToken")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val alphaId = given()
            .header("Authorization", "Bearer $adminToken")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Alpha", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $adminToken")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Alpha Child", "parentId" to alphaId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)

        given()
            .header("Authorization", "Bearer $adminToken")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Beta", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)

        val managerToken = createTenantManagerToken(alphaId)

        given()
            .header("Authorization", "Bearer $managerToken")
            .queryParam("recursive", "true")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .body("toString()", containsString("Alpha"))
            .body("toString()", containsString("Alpha Child"))
            .body("toString()", not(containsString("Beta")))
    }

    @Test
    fun testTenantManagerCannotGetUnscopedTenant() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = given()
            .header("Authorization", "Bearer $adminToken")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val alphaId = given()
            .header("Authorization", "Bearer $adminToken")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Alpha", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val betaId = given()
            .header("Authorization", "Bearer $adminToken")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Beta", "parentId" to rootId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val managerToken = createTenantManagerToken(alphaId)
        given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$betaId")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testTenantManagerCanGetAndUpdateLineageDefaults() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val rootId = given()
            .header("Authorization", "Bearer $adminToken")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("[0].id")

        val managerToken = createTenantManagerToken(rootId)

        given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(200)
            .body("safeScopePolicy", equalTo("AUTO"))
            .body("defaultFetchMode", equalTo("SUMMARY"))
            .body("defaultDepth", equalTo(20))
            .body("maxDepthCap", equalTo(200))
            .body("history.size()", equalTo(0))

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf(
                "safeScopePolicy" to "PIPELINE",
                "defaultFetchMode" to "DETAILS",
                "defaultDepth" to 40,
                "maxDepthCap" to 120
            ))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(200)
            .body("safeScopePolicy", equalTo("PIPELINE"))
            .body("defaultFetchMode", equalTo("DETAILS"))
            .body("defaultDepth", equalTo(40))
            .body("maxDepthCap", equalTo(120))
            .body("history.size()", equalTo(1))
            .body("history[0].changedByEmail", equalTo("tenant-admin@example.com"))
            .body("history[0].defaults.safeScopePolicy", equalTo("PIPELINE"))

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf("defaultDepth" to 500))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(400)
            .body("error", containsString("defaultDepth"))
    }
}
