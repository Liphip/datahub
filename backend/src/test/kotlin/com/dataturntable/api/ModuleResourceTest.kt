package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import io.quarkus.elytron.security.common.BcryptUtil

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*
import java.util.UUID

@QuarkusTest
class ModuleResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    fun createNonMemberToken(): String {
        val email = "module-viewer@example.com"
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

    private fun adminToken(): String = TestAuthHelpers.createAdminToken()

    private fun firstTenantId(token: String): String =
        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getList<Map<String, Any>>("")
            .first()["id"]
            .toString()

    private fun createModule(token: String, tenantId: String, name: String, moduleType: String = "PROCESSING"): String =
        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "name" to name,
                    "moduleType" to moduleType,
                    "tenantId" to tenantId
                )
            )
        .`when`()
            .post("/api/internal/v1/modules")
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id")
    
    @Test
    fun testListModulesRequiresAuth() {
        given()
        .queryParam("tenantId", "00000000-0000-0000-0000-000000000000")
        .`when`()
            .get("/api/internal/v1/modules")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetModuleNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/modules/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreateModuleRejectsInvalidModuleType() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "name" to "Module ${UUID.randomUUID()}",
                    "moduleType" to "invalid_type",
                    "tenantId" to "00000000-0000-0000-0000-000000000000"
                )
            )
        .`when`()
            .post("/api/internal/v1/modules")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid module type"))
    }

    @Test
    fun testListModulesRejectsInvalidTypeFilter() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getList<Map<String, Any>>("")
            .first()["id"]
            .toString()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("tenantId", tenantId)
            .queryParam("type", "not-a-type")
        .`when`()
            .get("/api/internal/v1/modules")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid module type"))
    }

    @Test
    fun testListModulesRejectsUnauthorizedTenantScope() {
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
            .getList<Map<String, Any>>("")
            .first()["id"]
            .toString()

        val outsiderToken = createNonMemberToken()
        given()
            .header("Authorization", "Bearer $outsiderToken")
            .queryParam("tenantId", tenantId)
        .`when`()
            .get("/api/internal/v1/modules")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testCreateVersionDoesNotDuplicateWhenFingerprintIsUnchanged() {
        val token = adminToken()
        val tenantId = firstTenantId(token)
        val moduleId = createModule(token, tenantId, "Fingerprint module ${UUID.randomUUID()}")

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "code" to "print('same')",
                    "requirements" to "pandas==2.2.2",
                    "versionTag" to "stable",
                    "config" to mapOf("mode" to "append")
                )
            )
        .`when`()
            .post("/api/internal/v1/modules/$moduleId/versions")
        .then()
            .statusCode(201)
            .body("createdNewVersion", equalTo(true))
            .body("version", equalTo(1))

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "code" to "print('same')",
                    "requirements" to "pandas==2.2.2",
                    "versionTag" to "stable",
                    "config" to mapOf("mode" to "append")
                )
            )
        .`when`()
            .post("/api/internal/v1/modules/$moduleId/versions")
        .then()
            .statusCode(200)
            .body("createdNewVersion", equalTo(false))
            .body("version", equalTo(1))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/modules/$moduleId/versions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
    }

    @Test
    fun testDefaultVersionCanBePromotedSeparately() {
        val token = adminToken()
        val tenantId = firstTenantId(token)
        val moduleId = createModule(token, tenantId, "Default module ${UUID.randomUUID()}")

        val versionOneId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("code" to "print('v1')", "versionTag" to "v1"))
        .`when`()
            .post("/api/internal/v1/modules/$moduleId/versions")
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id")

        val versionTwoId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("code" to "print('v2')", "versionTag" to "v2"))
        .`when`()
            .post("/api/internal/v1/modules/$moduleId/versions")
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
        .`when`()
            .post("/api/internal/v1/modules/$moduleId/versions/$versionTwoId/default")
        .then()
            .statusCode(200)
            .body("defaultVersionId", equalTo(versionTwoId))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/modules/$moduleId")
        .then()
            .statusCode(200)
            .body("defaultVersionId", equalTo(versionTwoId))
            .body("versions.find { it.id == '%s' }.versionTag".format(versionOneId), equalTo("v1"))
            .body("versions.find { it.id == '%s' }.versionTag".format(versionTwoId), equalTo("v2"))
    }
}
