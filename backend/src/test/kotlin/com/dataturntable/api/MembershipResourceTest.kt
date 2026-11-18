package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.*
import java.util.UUID

@QuarkusTest
class MembershipResourceTest {
    @jakarta.inject.Inject lateinit var em: jakarta.persistence.EntityManager


    @Test
    fun testListMembershipsRequiresAuth() {
        given()
        .`when`()
            .get("/api/internal/v1/tenants/00000000-0000-0000-0000-000000000000/memberships")
        .then()
            .statusCode(401)
    }

    @Test
    fun testCreateAndListMembershipsWithAdminAuth() {
        val token = TestAuthHelpers.createAdminToken()

        // Get existing tenants
        val tenants = given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()

        val tenantId = tenants.getList<Map<String, Any>>("").first()["id"].toString()

        // Create a role for the tenant
        val roleResp = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Test Role", "abilities" to listOf("tenant:manage")))
        .`when`()
            .post("/api/internal/v1/roles?tenantId=$tenantId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val roleId = roleResp.getString("id")

        // Create a new user to be a member
        val password = "password123"

        val userResp = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "member-${UUID.randomUUID()}@example.com", "password" to password))
        .`when`()
            .post("/api/internal/v1/auth-users")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()

        val userId = userResp.getString("id")
        val userEmail = userResp.getString("email")

        // Create membership
        val createResp = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("authUserId" to userId, "roleId" to roleId))
        .`when`()
            .post("/api/internal/v1/tenants/$tenantId/memberships")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("authUserEmail", equalTo(userEmail))
            .body("roleName", equalTo("Test Role"))
            .extract()
            .body()
            .jsonPath()

        val membershipId = createResp.getString("id")

        val memberToken = given().contentType(ContentType.JSON)
            .body(mapOf("email" to userEmail, "password" to password))
            .post("/api/internal/v1/auth/login").then().statusCode(200).extract().jsonPath().getString("token")
        val membershipPath = "/api/internal/v1/tenants/$tenantId/memberships/$membershipId"
        // Cross the expiry boundary without changing authorizationVersion, as time passing would.
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run {
            em.find(com.dataturntable.tenant.Membership::class.java, UUID.fromString(membershipId)).validUntil = java.time.Instant.now().minusSeconds(1)
        }
        given().header("Authorization", "Bearer $memberToken").get("/api/internal/v1/auth/me")
            .then().statusCode(200).body("abilities.size()", equalTo(0))
        given().header("Authorization", "Bearer $memberToken").get("/api/internal/v1/tenants")
            .then().statusCode(403)
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run {
            em.find(com.dataturntable.tenant.Membership::class.java, UUID.fromString(membershipId)).validUntil = null
        }
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("validFrom" to "2030-01-02T00:00:00Z", "validUntil" to "2030-01-01T00:00:00Z"))
            .patch(membershipPath).then().statusCode(400)
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf("validUntil" to "2020-01-01T00:00:00Z"))
            .patch(membershipPath).then().statusCode(200).body("effective", equalTo(false))
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run {
            val desired = em.createQuery("SELECT g.desiredState FROM GrafanaSyncState g WHERE g.scopeId = :id", String::class.java)
                .setParameter("id", UUID.fromString(membershipId)).singleResult
            org.junit.jupiter.api.Assertions.assertTrue(com.dataturntable.json.JsonObjectMappers.create().readTree(desired).path("deleted").asBoolean())
        }
        given().header("Authorization", "Bearer $memberToken").get("/api/internal/v1/auth/me")
            .then().statusCode(401) // Editing validity revokes existing tokens.
        val expiredToken = given().contentType(ContentType.JSON)
            .body(mapOf("email" to userEmail, "password" to password))
            .post("/api/internal/v1/auth/login").then().statusCode(200).extract().jsonPath().getString("token")
        given().header("Authorization", "Bearer $expiredToken").get("/api/internal/v1/auth/tenants")
            .then().statusCode(200).body("size()", equalTo(0))
        given().header("Authorization", "Bearer $expiredToken").get("/api/internal/v1/tenants")
            .then().statusCode(403)
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body(mapOf<String, Any?>("validUntil" to null))
            .patch(membershipPath).then().statusCode(200).body("effective", equalTo(true))

        // List memberships and assert presence
        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("abilities" to listOf("module:read")))
        .`when`()
            .patch("/api/internal/v1/tenants/$tenantId/memberships/$membershipId")
        .then()
            .statusCode(200)
            .body("id", equalTo(membershipId))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/$tenantId/memberships")
        .then()
            .statusCode(200)
            .body("size()", greaterThan(0))
            .body("find { it.id == '${membershipId}' }", notNullValue())
            .body("find { it.id == '${membershipId}' }.authUserEmail", equalTo(userEmail))
            .body("find { it.id == '${membershipId}' }.roleName", equalTo("Test Role"))
    }

    @Test
    fun testListMembershipsRejectsInvalidTenantId() {
        val token = TestAuthHelpers.createAdminToken()

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/not-a-uuid/memberships")
        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid tenantId"))
    }

    @Test
    fun testMembershipRejectsRoleFromAnotherTenantAndWrongTenantDeletePath() {
        val token = TestAuthHelpers.createAdminToken()

        val rootTenantId = given()
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

        val tenantAId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant A ${UUID.randomUUID()}", "parentId" to rootTenantId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val tenantBId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant B ${UUID.randomUUID()}", "parentId" to rootTenantId))
        .`when`()
            .post("/api/internal/v1/tenants")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val roleAId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant A Role", "abilities" to listOf("tenant:manage")))
        .`when`()
            .post("/api/internal/v1/roles?tenantId=$tenantAId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val roleBId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "Tenant B Role", "abilities" to listOf("tenant:manage")))
        .`when`()
            .post("/api/internal/v1/roles?tenantId=$tenantBId")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val password = "password123"
        val userId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "member-${UUID.randomUUID()}@example.com", "password" to password))
        .`when`()
            .post("/api/internal/v1/auth-users")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        val membershipId = given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("authUserId" to userId, "roleId" to roleAId))
        .`when`()
            .post("/api/internal/v1/tenants/$tenantAId/memberships")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .jsonPath()
            .getString("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body(mapOf("roleId" to roleBId))
        .`when`()
            .patch("/api/internal/v1/tenants/$tenantAId/memberships/$membershipId")
        .then()
            .statusCode(400)
            .body("error", equalTo("Role does not belong to tenant"))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .delete("/api/internal/v1/tenants/$tenantBId/memberships/$membershipId")
        .then()
            .statusCode(404)
            .body("error", equalTo("Membership not found"))

        given()
            .header("Authorization", "Bearer $token")
        .`when`()
            .get("/api/internal/v1/tenants/$tenantAId/memberships")
        .then()
            .statusCode(200)
            .body("find { it.id == '${membershipId}' }", notNullValue())
    }
}
