package com.dataturntable

import com.dataturntable.test.PostgresTestProfile
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Closes NFR-014: the default `mvn test` run uses H2 with Hibernate `drop-and-create`, which
 * never actually executes the Flyway migrations that define the real production schema (SRS
 * NFR-M6). This class boots the full
 * application against a real PostgreSQL instance (via Quarkus Dev Services/Testcontainers) with
 * schema generation set to `validate` and Flyway migrations applied at startup — so a broken
 * migration, a JPA entity that doesn't match the migrated schema, or a Postgres-only SQL feature
 * that behaves differently than assumed fails this test instead of silently passing against H2.
 *
 * Requires a running Docker (or compatible) daemon. Run with:
 *   mvn test -Dtest=PostgresMigrationBehaviorTest -Dquarkus.test.profile=postgres-it
 */
@QuarkusTest
@TestProfile(PostgresTestProfile::class)
@org.junit.jupiter.api.condition.EnabledIf("com.dataturntable.test.PostgresTestProfile#available")
class PostgresMigrationBehaviorTest {
    @jakarta.inject.Inject
    lateinit var oidcLogins: com.dataturntable.auth.OidcLoginService

    @jakarta.inject.Inject
    lateinit var pipelineData: com.dataturntable.service.PipelineDataService

    @Test
    fun `tenant table registration DB_SAVE and query work without database CREATE privileges`() {
        val token = TestAuthHelpers.createAdminToken()
        val rootId = given().header("Authorization", "Bearer $token").get("/api/internal/v1/tenants")
            .then().statusCode(200).extract().jsonPath().getString("[0].id")
        val tenantId = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("name" to "desktop-storage-${UUID.randomUUID()}", "parentId" to rootId))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("tableName" to "desktop_readings", "tableType" to "relational",
                "columns" to listOf(mapOf("name" to "v", "type" to "FLOAT"))))
            .post("/api/v1/data/$tenantId/tables/register").then().statusCode(201)
        val version = com.dataturntable.module.ModuleVersion().apply {
            config = """{"table":"desktop_readings","mode":"append"}"""
        }
        pipelineData.execute(version, com.dataturntable.module.ModuleType.DB_SAVE,
            mapOf("v" to 42), UUID.fromString(tenantId), "desktop-regression")
        given().header("Authorization", "Bearer $token").get("/api/v1/data/$tenantId/query?table=desktop_readings")
            .then().statusCode(200).body("data.size()", org.hamcrest.CoreMatchers.equalTo(1))
            .body("data[0].v", org.hamcrest.CoreMatchers.equalTo(42.0f))
        given().header("Authorization", "Bearer $token")
            .queryParam("table", "desktop_readings").queryParam("start", "2000-01-01T00:00:00Z")
            .queryParam("end", "2100-01-01T00:00:00Z")
            .get("/api/v1/data/$tenantId/query").then().statusCode(200)
            .body("data.size()", org.hamcrest.CoreMatchers.equalTo(1))
    }

    @Test
    fun `OIDC links and one-use handoffs persist against the migrated schema`() {
        val token = TestAuthHelpers.createAdminToken()
        val userId = given().header("Authorization", "Bearer $token").get("/api/internal/v1/auth/me")
            .then().statusCode(200).extract().jsonPath().getString("id")
        val issuer = "https://desktop-provider.example"
        val subject = "pg-subject-${UUID.randomUUID()}"
        val path = "/api/internal/v1/users/$userId/identities"
        val linkId = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("issuer" to issuer, "subject" to subject)).post(path)
            .then().statusCode(201).extract().jsonPath().getString("id")
        try {
            val handoff = oidcLogins.begin(issuer, subject)
            val result = oidcLogins.exchange(handoff.code, handoff.browser)
            org.junit.jupiter.api.Assertions.assertEquals(userId, result.user.id)
            org.junit.jupiter.api.Assertions.assertThrows(SecurityException::class.java) {
                oidcLogins.exchange(handoff.code, handoff.browser)
            }
        } finally {
            given().header("Authorization", "Bearer $token").delete("$path/$linkId").then().statusCode(204)
        }
    }

    @Test
    fun `application boots against the migrated PostgreSQL schema and JSONB, secret round-trips behave correctly`() {
        val adminToken = TestAuthHelpers.createAdminToken()

        val rootId = given().header("Authorization", "Bearer $adminToken")
        .`when`().get("/api/internal/v1/tenants")
        .then().statusCode(200).extract().jsonPath().getString("[0].id")

        val tenantId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("name" to "pg-it-tenant-${UUID.randomUUID()}", "parentId" to rootId))
        .`when`().post("/api/internal/v1/tenants")
        .then().statusCode(201).extract().jsonPath().getString("id")

        given().header("Authorization", "Bearer $adminToken")
        .`when`().get("/api/internal/v1/tenants/$tenantId")
        .then().statusCode(200).body("id", org.hamcrest.Matchers.equalTo(tenantId))

        // JSONB-backed pipeline graph (migration-defined column) round-trips on real PostgreSQL.
        val graph = mapOf("schemaVersion" to 1, "nodes" to emptyList<Any>(), "edges" to emptyList<Any>())
        val pipelineId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("name" to "pg-it-pipeline-${UUID.randomUUID()}", "graphDefinition" to graph))
        .`when`().post("/api/internal/v1/pipelines?tenantId=$tenantId")
        .then().statusCode(201).extract().jsonPath().getString("id")

        given().header("Authorization", "Bearer $adminToken")
        .`when`().get("/api/internal/v1/pipelines/$pipelineId")
        .then().statusCode(200).body("graphDefinition.schemaVersion", org.hamcrest.Matchers.equalTo(1))

        // Encrypted, versioned secret storage (migration V17) round-trips through real Postgres.
        val secretId = given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("name" to "pg-it-secret-${UUID.randomUUID()}", "value" to "pg-it-value"))
        .`when`().post("/api/internal/v1/secrets?tenantId=$tenantId")
        .then().statusCode(201).extract().jsonPath().getString("id")

        given().header("Authorization", "Bearer $adminToken").contentType(ContentType.JSON)
            .body(mapOf("value" to "pg-it-rotated-value"))
        .`when`().post("/api/internal/v1/secrets/$secretId/rotate?tenantId=$tenantId")
        .then().statusCode(200)
    }
}
