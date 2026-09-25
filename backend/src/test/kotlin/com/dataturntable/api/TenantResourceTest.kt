package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertHistory
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
import io.quarkus.elytron.security.common.BcryptUtil

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
            em.createQuery("DELETE FROM AlertHistory").executeUpdate()
            em.createQuery("DELETE FROM Tenant").executeUpdate()
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
        }
    }

    fun createNonAdminToken(): String {
        val email = "viewer@example.com"
        val password = "ViewerPass!123"

        QuarkusTransaction.requiringNew().run {
            val user = User().apply {
                this.email = email
                this.passwordHash = BcryptUtil.bcryptHash(password)
                this.isActive = true
                this.createdAt = java.time.Instant.now()
                this.updatedAt = java.time.Instant.now()
            }
            em.persist(user)
            em.flush()
        }

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to email, "password" to password))
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
        val password = "TenantPass!123"

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
                this.passwordHash = BcryptUtil.bcryptHash(password)
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
            .body(mapOf("email" to email, "password" to password, "tenantId" to tenantId))
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
            .body("version", notNullValue())
            .body("safeScopePolicy", equalTo("AUTO"))
            .body("defaultFetchMode", equalTo("SUMMARY"))
            .body("defaultDepth", equalTo(20))
            .body("maxDepthCap", equalTo(200))
            .body("history.size()", equalTo(0))

        given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics-policy")
        .then()
            .statusCode(200)
            .body("version", notNullValue())
            .body("history.size()", equalTo(0))
            .body("conflictWatchRate", equalTo(0.35f))
            .body("conflictHotRate", equalTo(0.5f))
            .body("conflictTrendDelta", equalTo(0.15f))
            .body("minPatchSamplesForHot", equalTo(3))
            .body("pollOverfetchMinReads", equalTo(20))
            .body("pollOverfetchRate", equalTo(0.9f))

        val baselineVersion = given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("version")

        val etag = given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .extract()
            .header("ETag")

        given()
            .header("Authorization", "Bearer $managerToken")
            .header("If-None-Match", etag)
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(304)

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf(
                "ifVersion" to baselineVersion,
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
            .body("version", notNullValue())
            .body("history.size()", equalTo(1))
            .body("history[0].changedByEmail", equalTo("tenant-admin@example.com"))
            .body("history[0].changedFields", hasItems("safeScopePolicy", "defaultFetchMode", "defaultDepth", "maxDepthCap"))
            .body("history[0].defaults.safeScopePolicy", equalTo("PIPELINE"))

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf("ifVersion" to baselineVersion, "defaultDepth" to 30))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(409)
            .body("error", containsString("updated by another user"))
            .body("currentVersion", notNullValue())

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf("defaultDepth" to 500))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults")
        .then()
            .statusCode(400)
            .body("error", containsString("defaultDepth"))

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf("conflictHotRate" to 2.0))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics-policy")
        .then()
            .statusCode(400)
            .body("error", containsString("conflictHotRate"))

        val policyVersion = given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics-policy")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("version")

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf(
                "ifVersion" to policyVersion,
                "conflictHotRate" to 0.4,
                "minPatchSamplesForHot" to 1,
                "pollOverfetchMinReads" to 10
            ))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics-policy")
        .then()
            .statusCode(200)
            .body("version", notNullValue())
            .body("history.size()", equalTo(1))
            .body("history[0].changedByEmail", equalTo("tenant-admin@example.com"))
            .body("history[0].changedFields", hasItems("conflictHotRate", "minPatchSamplesForHot", "pollOverfetchMinReads"))
            .body("conflictWatchRate", equalTo(0.35f))
            .body("conflictHotRate", equalTo(0.4f))
            .body("minPatchSamplesForHot", equalTo(1))
            .body("pollOverfetchMinReads", equalTo(10))

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf("ifVersion" to policyVersion, "conflictTrendDelta" to 0.4))
        .`when`()
            .patch("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics-policy")
        .then()
            .statusCode(409)
            .body("error", containsString("updated by another user"))
            .body("currentVersion", notNullValue())

        given()
            .header("Authorization", "Bearer $managerToken")
        .`when`()
            .get("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics")
        .then()
            .statusCode(200)
            .body("tenantId", equalTo(rootId))
            .body("get200Count", equalTo(3))
            .body("get304Count", equalTo(1))
            .body("patchSuccessCount", equalTo(1))
            .body("patchConflictCount", equalTo(1))
            .body("readTotalCount", equalTo(4))
            .body("resetCount", equalTo(0))
            .body("lastResetAt", nullValue())
            .body("windows.h1.get200Count", equalTo(3))
            .body("windows.h1.get304Count", equalTo(1))
            .body("windows.h1.patchSuccessCount", equalTo(1))
            .body("windows.h1.patchConflictCount", equalTo(1))
            .body("windows.h24.get200Count", equalTo(3))
            .body("windows.h24.get304Count", equalTo(1))
            .body("advisory.health", equalTo("HOT"))
            .body("advisory.reasons", hasItems("CONFLICT_SPIKE"))
            .body("advisory.recommendedActions", hasItems("REFRESH_BEFORE_SAVE", "COORDINATE_EDITOR_WINDOWS"))
            .body("advisory.policy.conflictHotRate", equalTo(0.4f))
            .body("advisory.policy.minPatchSamplesForHot", equalTo(1))
            .body("advisory.conflictRateDelta", notNullValue())
            .body("lastUpdatedAt", notNullValue())

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(emptyMap<String, Any>())
        .`when`()
            .post("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics/reset")
        .then()
            .statusCode(200)
            .body("tenantId", equalTo(rootId))
            .body("get200Count", equalTo(0))
            .body("get304Count", equalTo(0))
            .body("patchSuccessCount", equalTo(0))
            .body("patchConflictCount", equalTo(0))
            .body("readTotalCount", equalTo(0))
            .body("resetCount", equalTo(1))
            .body("lastResetAt", notNullValue())
            .body("resetHistory.size()", equalTo(1))
            .body("resetHistory[0].resetByEmail", equalTo("tenant-admin@example.com"))
            .body("windows.h1.get200Count", equalTo(0))
            .body("windows.h1.get304Count", equalTo(0))
            .body("windows.h1.patchSuccessCount", equalTo(0))
            .body("windows.h1.patchConflictCount", equalTo(0))
            .body("advisory.health", equalTo("STABLE"))
            .body("advisory.reasons.size()", equalTo(0))
            .body("advisory.recommendedActions", hasItems("NONE"))
            .body("lastUpdatedAt", notNullValue())

        given()
            .header("Authorization", "Bearer $managerToken")
            .contentType(ContentType.JSON)
            .body(mapOf("reason" to "Testing reset with reason"))
        .`when`()
            .post("/api/internal/v1/tenants/$rootId/lineage-defaults/diagnostics/reset")
        .then()
            .statusCode(200)
            .body("resetCount", equalTo(2))
            .body("resetHistory.size()", equalTo(2))
            .body("resetHistory[1].resetByEmail", equalTo("tenant-admin@example.com"))
            .body("resetHistory[1].reason", equalTo("Testing reset with reason"))
    }
}
