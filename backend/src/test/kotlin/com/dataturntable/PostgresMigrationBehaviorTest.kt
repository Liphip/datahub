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
 * never actually executes the Flyway migrations that define the real production schema (see
 * production-ready-v1-status.md, "Historical architectural note"). This class boots the full
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
class PostgresMigrationBehaviorTest {
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
