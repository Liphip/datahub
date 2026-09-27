package com.dataturntable.test

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager

object TestAuthHelpers {
    fun createAdminToken(): String {
        val password = "AdminPass!123"

        val setupBody = """
            {
                "email": "admin@example.com",
                "password": "$password"
            }
        """.trimIndent()

        val setupResponse = given()
            .contentType(ContentType.JSON)
            .body(setupBody)
        .`when`()
            .post("/api/internal/v1/auth/setup")
        .then()
            .extract()
            .body()
            .jsonPath()

        val setupError = setupResponse.getString("error")
        if (setupError != null && !setupError.contains("already completed", ignoreCase = true)) {
            throw IllegalStateException("Unable to initialize test admin: $setupError")
        }

        if (setupError != null && !adminExists()) {
            // Some other test left behind an unrelated User row, tripping the
            // production single-use setup guard before this admin account could
            // be created. Clear that stray state and retry setup once.
            deleteNonAdminUsers()
            val retryResponse = given()
                .contentType(ContentType.JSON)
                .body(setupBody)
            .`when`()
                .post("/api/internal/v1/auth/setup")
            .then()
                .extract()
                .body()
                .jsonPath()
            val retryError = retryResponse.getString("error")
            if (retryError != null && !retryError.contains("already completed", ignoreCase = true)) {
                throw IllegalStateException("Unable to initialize test admin after clearing stray users: $retryError")
            }
        }

        return given()
            .contentType(ContentType.JSON)
            .body(mapOf("email" to "admin@example.com", "password" to password))
        .`when`()
            .post("/api/internal/v1/auth/login")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .jsonPath()
            .getString("token")
    }

    private fun adminExists(): Boolean = QuarkusTransaction.requiringNew().call {
        val em = Arc.container().instance(EntityManager::class.java).get()
        em.createQuery("SELECT count(u) FROM User u WHERE u.email = 'admin@example.com'", java.lang.Long::class.java)
            .singleResult.toLong() > 0
    }

    private fun deleteNonAdminUsers() {
        QuarkusTransaction.requiringNew().run {
            val em = Arc.container().instance(EntityManager::class.java).get()
            val strayIds = em.createQuery(
                "SELECT u.id FROM User u WHERE u.email <> 'admin@example.com'",
                java.util.UUID::class.java
            ).resultList
            if (strayIds.isEmpty()) return@run
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate()
            try {
                em.createQuery("DELETE FROM Membership m WHERE m.authUser.id IN :ids").setParameter("ids", strayIds).executeUpdate()
                em.createQuery("DELETE FROM User u WHERE u.id IN :ids").setParameter("ids", strayIds).executeUpdate()
            } finally {
                em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate()
            }
        }
    }
}
