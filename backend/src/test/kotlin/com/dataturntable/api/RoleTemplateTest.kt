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
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.junit.jupiter.api.Test
import java.util.UUID

/** IAM-05: built-in role templates are marked, cannot be modified, and can be cloned and disabled. */
@QuarkusTest
class RoleTemplateTest {
    @Inject lateinit var em: EntityManager

    private val adminToken by lazy { TestAuthHelpers.createAdminToken() }
    private fun api(token: String = adminToken) = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)

    private fun tenant(): String {
        val root = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        return api().body(mapOf("name" to "roles-${UUID.randomUUID()}", "parentId" to root))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")
    }

    private fun roleId(tenantId: String, name: String): String =
        api().get("/api/internal/v1/roles?tenantId=$tenantId").then().statusCode(200)
            .extract().jsonPath().getString("find { it.name == '$name' }.id")

    private data class Member(val email: String, val password: String, val userId: String)

    private fun member(tenantId: String, roleId: String): Member {
        val email = "role-${UUID.randomUUID()}@example.com"
        val password = "MemberPass!123"
        var userId = ""
        QuarkusTransaction.requiringNew().run {
            val user = User().apply { this.email = email; passwordHash = BcryptUtil.bcryptHash(password) }
            em.persist(user)
            em.persist(Membership().apply {
                authUser = user
                tenant = em.find(Tenant::class.java, UUID.fromString(tenantId))
                role = em.find(Role::class.java, UUID.fromString(roleId))
                abilities = "[]"
            })
            userId = user.id.toString()
        }
        return Member(email, password, userId)
    }

    private fun login(member: Member): String =
        given().contentType(ContentType.JSON).body(mapOf("email" to member.email, "password" to member.password))
            .post("/api/internal/v1/auth/login").then().statusCode(200).extract().jsonPath().getString("token")

    @Test
    fun `the roles a tenant starts with are built-in templates that cannot be edited or deleted`() {
        val tenantId = tenant()
        api().get("/api/internal/v1/roles?tenantId=$tenantId").then().statusCode(200)
            .body("findAll { it.isSystem }.name", hasItems("Viewer", "Editor", "Tenant Admin"))
        val viewer = roleId(tenantId, "Viewer")

        api().body(mapOf("name" to "Renamed")).patch("/api/internal/v1/roles/$viewer").then().statusCode(403)
        api().body(mapOf("abilities" to listOf("*"))).patch("/api/internal/v1/roles/$viewer").then().statusCode(403)
        // Smuggling a change in next to the one permitted field is refused as a whole.
        api().body(mapOf("isDisabled" to true, "abilities" to listOf("*"))).patch("/api/internal/v1/roles/$viewer").then().statusCode(403)
        api().delete("/api/internal/v1/roles/$viewer").then().statusCode(403)
        api().get("/api/internal/v1/roles/$viewer").then().statusCode(200).body("name", equalTo("Viewer")).body("isDisabled", equalTo(false))
    }

    @Test
    fun `a clone of a template is an ordinary editable role with the same abilities`() {
        val tenantId = tenant()
        val viewer = roleId(tenantId, "Viewer")

        val clone = api().body(mapOf("name" to "Auditing viewer")).post("/api/internal/v1/roles/$viewer/clone")
            .then().statusCode(201).body("isSystem", equalTo(false)).body("isDefault", equalTo(false))
            .body("abilities", hasItems("pipeline:read", "data:read")).extract().jsonPath().getString("id")
        api().body(mapOf("name" to "Auditing viewer")).post("/api/internal/v1/roles/$viewer/clone").then().statusCode(409)

        api().body(mapOf("abilities" to listOf("pipeline:read", "audit:read"))).patch("/api/internal/v1/roles/$clone")
            .then().statusCode(200).body("abilities", hasItems("audit:read"))
        api().delete("/api/internal/v1/roles/$clone").then().statusCode(204)
    }

    @Test
    fun `a disabled role grants nothing and cannot be assigned until it is enabled again`() {
        val tenantId = tenant()
        val viewer = roleId(tenantId, "Viewer")
        val holder = member(tenantId, viewer)
        api(login(holder)).get("/api/internal/v1/pipelines?tenantId=$tenantId").then().statusCode(200)

        api().body(mapOf("isDisabled" to true)).patch("/api/internal/v1/roles/$viewer").then().statusCode(200).body("isDisabled", equalTo(true))
        // The definition stays visible so it can still be inspected and cloned.
        api().get("/api/internal/v1/roles/$viewer").then().statusCode(200).body("abilities", hasItems("pipeline:read"))
        api(login(holder)).get("/api/internal/v1/pipelines?tenantId=$tenantId").then().statusCode(403)

        // A fresh user cannot be given the disabled role.
        val newcomer = member(tenant(), roleId(tenantId, "Editor"))
        api().body(mapOf("authUserId" to newcomer.userId, "roleId" to viewer))
            .post("/api/internal/v1/tenants/$tenantId/memberships").then().statusCode(400).body("error", equalTo("Role is disabled"))

        api().body(mapOf("isDisabled" to false)).patch("/api/internal/v1/roles/$viewer").then().statusCode(200)
        api(login(holder)).get("/api/internal/v1/pipelines?tenantId=$tenantId").then().statusCode(200)
    }

    @Test
    fun `an administrator cannot disable the role their own access comes from`() {
        val tenantId = tenant()
        val adminRole = roleId(tenantId, "Tenant Admin")
        val tenantAdmin = member(tenantId, adminRole)

        api(login(tenantAdmin)).body(mapOf("isDisabled" to true)).patch("/api/internal/v1/roles/$adminRole").then().statusCode(409)
        api(login(tenantAdmin)).body(mapOf("isDisabled" to true)).patch("/api/internal/v1/roles/${roleId(tenantId, "Viewer")}").then().statusCode(200)
    }
}
