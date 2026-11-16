package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.elytron.security.common.BcryptUtil
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** AUD-07/AUD-08: the audit trail is searchable and exportable, and visible only within `audit:read` scope. */
@QuarkusTest
class AuditResourceTest {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var audit: com.dataturntable.audit.AuditService

    private val adminToken by lazy { TestAuthHelpers.createAdminToken() }
    private fun api(token: String = adminToken) = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)

    private fun tenant(): String {
        val root = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        return api().body(mapOf("name" to "audit-${UUID.randomUUID()}", "parentId" to root))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")
    }

    /** Leaves an `audittest.created` audit record in [tenantId]. */
    private fun auditedAction(tenantId: String, name: String) {
        audit.record(null, UUID.fromString(tenantId), "audittest.created", "thing", "thing-1", mapOf("name" to name))
    }

    /** A user who is a member of [tenantId] only, with exactly [abilities]. */
    private fun memberToken(tenantId: String, abilities: String): String {
        val email = "audit-${UUID.randomUUID()}@example.com"
        val password = "MemberPass!123"
        QuarkusTransaction.requiringNew().run {
            val t = em.find(Tenant::class.java, UUID.fromString(tenantId))
            val role = Role().apply { tenant = t; name = "audit-test-${UUID.randomUUID()}"; this.abilities = abilities }
            em.persist(role)
            val user = User().apply { this.email = email; passwordHash = BcryptUtil.bcryptHash(password) }
            em.persist(user)
            em.persist(Membership().apply { authUser = user; tenant = t; this.role = role; this.abilities = "[]" })
        }
        return given().contentType(ContentType.JSON).body(mapOf("email" to email, "password" to password))
            .post("/api/internal/v1/auth/login").then().statusCode(200).extract().jsonPath().getString("token")
    }

    @Test
    fun `search returns a tenant's records and honours action, text and time filters`() {
        val tenantId = tenant()
        val marker = "key-${UUID.randomUUID()}"
        auditedAction(tenantId, marker)

        api().get("/api/internal/v1/audit?tenantId=$tenantId&action=audittest.created")
            .then().statusCode(200)
            .body("total", equalTo(1))
            .body("items[0].action", equalTo("audittest.created"))
            .body("items[0].tenantId", equalTo(tenantId))
            .body("items[0].metadata.name", equalTo(marker))

        api().get("/api/internal/v1/audit?tenantId=$tenantId&action=audittest.*").then().statusCode(200).body("total", equalTo(1))
        api().get("/api/internal/v1/audit?tenantId=$tenantId&action=audittest").then().statusCode(200).body("total", equalTo(0))
        api().get("/api/internal/v1/audit?tenantId=$tenantId&q=$marker").then().statusCode(200).body("total", equalTo(1))
        api().get("/api/internal/v1/audit?tenantId=$tenantId&q=no-such-text-anywhere").then().statusCode(200).body("total", equalTo(0))
        api().get("/api/internal/v1/audit?tenantId=$tenantId&to=2000-01-01T00:00:00Z").then().statusCode(200).body("total", equalTo(0))
        api().get("/api/internal/v1/audit?tenantId=$tenantId&from=not-a-date").then().statusCode(400)
        // % and _ in the search text are literals, not wildcards.
        api().get("/api/internal/v1/audit?tenantId=$tenantId&q=%25").then().statusCode(200).body("total", equalTo(0))
    }

    @Test
    fun `visibility follows audit read scope`() {
        val tenantA = tenant()
        val tenantB = tenant()
        auditedAction(tenantA, "a-key")
        auditedAction(tenantB, "b-key")

        val auditor = memberToken(tenantB, "[\"audit:read\"]")
        val viewer = memberToken(tenantB, "[\"pipeline:read\"]")

        api(auditor).get("/api/internal/v1/audit?tenantId=$tenantB").then().statusCode(200)
            .body("total", greaterThanOrEqualTo(1)).body("items.tenantId.unique()", equalTo(listOf(tenantB)))
        // Another tenant, and the installation-wide view, are out of scope for a tenant auditor.
        api(auditor).get("/api/internal/v1/audit?tenantId=$tenantA").then().statusCode(403)
        api(auditor).get("/api/internal/v1/audit").then().statusCode(403)
        api(auditor).get("/api/internal/v1/audit/export?tenantId=$tenantA&format=csv").then().statusCode(403)
        // Membership without the ability is not enough.
        api(viewer).get("/api/internal/v1/audit?tenantId=$tenantB").then().statusCode(403)
        given().get("/api/internal/v1/audit?tenantId=$tenantB").then().statusCode(401)
        // The platform administrator sees everything, including records without a tenant.
        api().get("/api/internal/v1/audit?action=auth.login.succeeded").then().statusCode(200).body("total", greaterThanOrEqualTo(1))
    }

    @Test
    fun `an export is delivered as csv or json and is itself audited`() {
        val tenantId = tenant()
        auditedAction(tenantId, "=cmd, \"quoted\"")

        val csv = api().get("/api/internal/v1/audit/export?tenantId=$tenantId&format=csv&action=audittest.created")
            .then().statusCode(200).header("Content-Type", containsString("text/csv"))
            .header("Content-Disposition", containsString(".csv")).extract().asString()
        val lines = csv.trim().lines()
        assertEquals("occurredAt,action,outcome,resourceType,resourceId,actorId,tenantId,requestId,metadata", lines[0])
        assertEquals(2, lines.size, "one header and one record: $csv")
        assertTrue(lines[1].contains(",audittest.created,"))
        assertTrue(lines[1].contains("\"\""), "embedded quotes are doubled: ${lines[1]}")

        api().get("/api/internal/v1/audit/export?tenantId=$tenantId&format=json&action=audittest.created")
            .then().statusCode(200).body("size()", equalTo(1)).body("[0].action", equalTo("audittest.created"))
        api().get("/api/internal/v1/audit/export?tenantId=$tenantId&format=xml").then().statusCode(400)

        api().get("/api/internal/v1/audit?tenantId=$tenantId&action=audit.exported")
            .then().statusCode(200).body("total", equalTo(2)).body("items[0].metadata.records", equalTo(1))
    }
}
