package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.util.UUID

@QuarkusTest
class DataResourceTest {

    @PersistenceContext
    lateinit var em: EntityManager

    @AfterEach
    fun cleanupAdHocUsers() {
        QuarkusTransaction.requiringNew().run {
            val userIds = em.createQuery("SELECT u.id FROM User u WHERE u.email LIKE 'data-outsider-%@example.com'", UUID::class.java).resultList
            if (userIds.isEmpty()) return@run
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
            try {
                em.createQuery("DELETE FROM Membership m WHERE m.authUser.id IN :ids").setParameter("ids", userIds).executeUpdate()
                em.createQuery("DELETE FROM User u WHERE u.id IN :ids").setParameter("ids", userIds).executeUpdate()
            } finally {
                em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
            }
        }
    }

    private fun createNonMemberToken(): String {
        val email = "data-outsider-${java.util.UUID.randomUUID()}@example.com"
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

    private fun resolveTenantId(token: String): String {
        return given()
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
    }

    @Test
    fun testListTablesRequiresAuth() {
        given()
        .`when`()
            .get("/api/v1/data/00000000-0000-0000-0000-000000000000/tables")
        .then()
            .statusCode(401)
    }

    @Test
    fun testListTablesRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/v1/data/not-a-uuid/tables")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testListBlobsRejectsInvalidPagination() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("limit", 0)
        .`when`()
            .get("/api/v1/data/$tenantId/blobs")
        .then()
            .statusCode(400)
            .body("error", equalTo("limit must be between 1 and 200 and page must be non-negative"))
    }

    @Test
    fun testMissingBlobIsMarkedUnavailableInsteadOfSilentlyRemainingAvailable() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val blobId = UUID.randomUUID()
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "INSERT INTO data_blobs (id, tenant_id, name, content_type, size_bytes, storage_path, status, created_at, updated_at) " +
                    "VALUES (:id, :tenantId, 'missing.txt', 'text/plain', 1, :path, 'AVAILABLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            )
                .setParameter("id", blobId)
                .setParameter("tenantId", UUID.fromString(tenantId))
                .setParameter("path", "${System.getProperty("java.io.tmpdir")}/dataturntable-missing-$blobId")
                .executeUpdate()
        }

        given().header("Authorization", "Bearer $token")
        .`when`().get("/api/v1/data/$tenantId/blobs/$blobId/download")
        .then().statusCode(409)

        QuarkusTransaction.requiringNew().run {
            val status = em.createNativeQuery("SELECT status FROM data_blobs WHERE id = :id")
                .setParameter("id", blobId)
                .singleResult
            org.junit.jupiter.api.Assertions.assertEquals("MISSING", status.toString())
        }
    }

    @Test
    fun testRecoveryTombstonesStaleMissingBlobMetadata() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val blobId = UUID.randomUUID()
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "INSERT INTO data_blobs (id, tenant_id, name, content_type, size_bytes, storage_path, status, created_at, updated_at) " +
                    "VALUES (:id, :tenantId, 'stale.txt', 'text/plain', 1, :path, 'MISSING', CURRENT_TIMESTAMP, DATEADD('DAY', -31, CURRENT_TIMESTAMP))"
            )
                .setParameter("id", blobId)
                .setParameter("tenantId", UUID.fromString(tenantId))
                .setParameter("path", "${System.getProperty("java.io.tmpdir")}/dataturntable-stale-$blobId")
                .executeUpdate()
        }

        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON).body(emptyMap<String, Any>())
        .`when`().post("/api/v1/data/$tenantId/blobs/recover")
        .then().statusCode(200).body("purged", equalTo(1)).body("retained", equalTo(0))
    }

    @Test
    fun testQueryRejectsInvalidTableName() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "bad-table;drop")
        .`when`()
            .get("/api/v1/data/$tenantId/query")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid table name"))
    }

    @Test
    fun testQueryRejectsInvalidStartTimestamp() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "measurements")
            .queryParam("start", "not-a-timestamp")
        .`when`()
            .get("/api/v1/data/$tenantId/query")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid start timestamp"))
    }

    @Test
    fun testListTablesRejectsUnauthorizedTenantScope() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val outsiderToken = createNonMemberToken()

        given()
            .header("Authorization", "Bearer $outsiderToken")
        .`when`()
            .get("/api/v1/data/$tenantId/tables")
        .then()
            .statusCode(403)
            .body("error", equalTo("Forbidden"))
    }

    @Test
    fun testSummaryRejectsInvalidNumericField() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", "measurements")
            .queryParam("numericField", "value;drop")
        .`when`()
            .get("/api/v1/data/$tenantId/summary")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid numericField"))
    }

    @Test
    fun testRegisterInsertAndQueryDataThroughPublicApi() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val tableName = "sensor_${UUID.randomUUID().toString().replace("-", "_").take(12)}"
        val timestamp = Instant.parse("2026-03-23T12:00:00Z").toString()

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", tableName)
            .contentType(ContentType.JSON)
            .body(
                listOf(
                    mapOf(
                        "time" to timestamp,
                        "device_id" to "device-a",
                        "value" to 42.5,
                        "tags" to mapOf("location" to "lab")
                    )
                )
            )
        .`when`()
            .post("/api/v1/data/$tenantId/insert")
        .then()
            .statusCode(200)
            .body("inserted", equalTo(1))
            .body("table", equalTo(tableName))

        given()
            .header("Authorization", "Bearer $token")
            .queryParam("table", tableName)
            .queryParam("limit", 10)
        .`when`()
            .get("/api/v1/data/$tenantId/query")
        .then()
            .statusCode(200)
            .body("count", equalTo(1))
            .body("data[0].device_id", equalTo("device-a"))
    }

    @Test
    fun testRegisterRelationalTableRejectsInvalidOrDuplicateColumnNames() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val tableName = "rel_${UUID.randomUUID().toString().replace("-", "_").take(12)}"

        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf(
                "tableName" to tableName,
                "tableType" to "relational",
                "columns" to listOf(mapOf("name" to "device-id", "type" to "TEXT"))
            ))
        .`when`().post("/api/v1/data/$tenantId/tables/register")
        .then().statusCode(400).body("error", equalTo("Invalid column name"))

        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf(
                "tableName" to tableName,
                "tableType" to "relational",
                "columns" to listOf(mapOf("name" to "device_id", "type" to "TEXT"), mapOf("name" to "DEVICE_ID", "type" to "TEXT"))
            ))
        .`when`().post("/api/v1/data/$tenantId/tables/register")
        .then().statusCode(400).body("error", equalTo("Duplicate column name: DEVICE_ID"))
    }

    @Test
    fun testRegisterTableUpsertsRegistryEntryOnReRegistration() {
        val token = TestAuthHelpers.createAdminToken()
        val tenantId = resolveTenantId(token)
        val tableName = "reg_${UUID.randomUUID().toString().replace("-", "_").take(12)}"

        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("tableName" to tableName, "tableType" to "timeseries"))
        .`when`().post("/api/v1/data/$tenantId/tables/register")
        .then().statusCode(201).body("tableType", equalTo("timeseries"))

        // Re-registering the same table with a different type exercises the ON CONFLICT / H2
        // upsert fallback path rather than a plain insert.
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf(
                "tableName" to tableName,
                "tableType" to "relational",
                "columns" to listOf(mapOf("name" to "device_id", "type" to "TEXT"))
            ))
        .`when`().post("/api/v1/data/$tenantId/tables/register")
        .then().statusCode(201).body("tableType", equalTo("relational"))

        given().header("Authorization", "Bearer $token")
        .`when`().get("/api/v1/data/$tenantId/tables")
        .then().statusCode(200)
            .body("tables.find { it.name == '$tableName' }.type", equalTo("relational"))
    }

}
