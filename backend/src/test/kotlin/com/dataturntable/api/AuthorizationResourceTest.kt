package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class AuthorizationResourceTest {
    @PersistenceContext lateinit var em: EntityManager

    @AfterEach
    fun cleanupAdHocUsers() {
        QuarkusTransaction.requiringNew().run {
            val userIds = em.createQuery(
                "SELECT u.id FROM User u WHERE u.email LIKE 'explanation-%@example.com'",
                UUID::class.java
            ).resultList
            if (userIds.isNotEmpty()) {
                em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
                try {
                    em.createQuery("DELETE FROM Membership m WHERE m.authUser.id IN :ids").setParameter("ids", userIds).executeUpdate()
                    em.createQuery("DELETE FROM User u WHERE u.id IN :ids").setParameter("ids", userIds).executeUpdate()
                } finally {
                    em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
                }
            }
        }
    }

    @Test
    fun `explanation reports effective grants and their user membership and role provenance`() {
        val adminToken = TestAuthHelpers.createAdminToken()
        val tenantId = given().header("Authorization", "Bearer $adminToken")
            .`when`().get("/api/internal/v1/tenants")
            .then().statusCode(200).extract().jsonPath().getString("[0].id")
        val targetId = QuarkusTransaction.requiringNew().call {
            val tenant = em.find(Tenant::class.java, UUID.fromString(tenantId))
            val role = Role().apply {
                this.tenant = tenant
                name = "Explanation role ${UUID.randomUUID()}"
                abilities = "[\"role:grant\"]"
            }
            val target = User().apply {
                email = "explanation-${UUID.randomUUID()}@example.com"
                passwordHash = "not-used"
                abilities = "[\"user:grant\"]"
                isActive = true
            }
            em.persist(role)
            em.persist(target)
            em.persist(Membership().apply {
                authUser = target
                this.tenant = tenant
                this.role = role
                abilities = "[\"membership:grant\"]"
            })
            em.flush()
            target.id.toString()
        }

        given().header("Authorization", "Bearer $adminToken")
            .queryParam("userId", targetId)
            .queryParam("tenantId", tenantId)
            .queryParam("ability", "membership:grant")
        .`when`().get("/api/internal/v1/authorization/explain")
        .then().statusCode(200)
            .body("granted", equalTo(true))
            .body("sources.user", containsInAnyOrder("user:grant"))
            .body("sources.membership", containsInAnyOrder("membership:grant"))
            .body("sources.role", containsInAnyOrder("role:grant"))
            .body("effectiveAbilities", containsInAnyOrder("user:grant", "membership:grant", "role:grant"))
    }
}
