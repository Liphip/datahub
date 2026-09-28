package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class UserResourceRbaCTest {

    @PersistenceContext
    lateinit var em: EntityManager

    @BeforeEach
    @Transactional
    fun resetIdentityFixture() {
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
        try {
            em.createQuery("DELETE FROM ApiKey").executeUpdate()
            em.createQuery("DELETE FROM Membership").executeUpdate()
            em.createQuery("DELETE FROM User").executeUpdate()
            em.createQuery("DELETE FROM Role").executeUpdate()
            em.createQuery("DELETE FROM Tenant").executeUpdate()
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
        }
    }

    @Test
    fun createUserWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("email" to "a@b.com", "password" to "x"))
            .`when`().post("/api/internal/v1/auth-users")
            .then()
            .statusCode(401)
    }

    @Test
    fun listUsersWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .`when`().get("/api/internal/v1/auth-users?tenantId=00000000-0000-0000-0000-000000000001")
            .then()
            .statusCode(401)
    }

    @Test
    fun createUserMissingEmailReturnsBadRequestWithAuth() {
        val token = TestAuthHelpers.createAdminToken()

        RestAssured.given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(mapOf("password" to "x"))
            .`when`().post("/api/internal/v1/auth-users")
            .then()
            .statusCode(400)
            .body("error", equalTo("Email is required"))
    }
}
