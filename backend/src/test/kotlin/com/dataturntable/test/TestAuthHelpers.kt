package com.dataturntable.test

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.arc.Arc
import io.quarkus.elytron.security.common.BcryptUtil
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import jakarta.persistence.EntityManager
import io.restassured.http.ContentType

object TestAuthHelpers {
    fun createAdminToken(): String {
        val password = "AdminPass!123"

        // Test classes that exercise setup delete all users. Recreate the admin in the oldest
        // root tenant (the one "[0]" of the tenant list resolves to) instead of running setup
        // again, which would create another root tenant; always reset the known password.
        QuarkusTransaction.requiringNew().run {
            val em = Arc.container().instance(EntityManager::class.java).get()
            val hash = BcryptUtil.bcryptHash(password)
            val root = em.createQuery("SELECT t FROM Tenant t WHERE t.parent IS NULL ORDER BY t.createdAt, t.name", Tenant::class.java)
                .setMaxResults(1).resultList.firstOrNull()
                ?: Tenant().apply { name = "Root Tenant"; createdAt = java.time.Instant.now(); updatedAt = java.time.Instant.now() }.also { em.persist(it) }
            val adminRole = em.createQuery("SELECT r FROM Role r WHERE r.tenant = :t AND r.name = 'Tenant Admin'", Role::class.java)
                .setParameter("t", root).resultList.firstOrNull()
                ?: Role().apply { tenant = root; name = "Tenant Admin"; abilities = "[\"*\"]"; isSystem = true }
                    .also { em.persist(it) }
            val user = em.createQuery("SELECT u FROM User u WHERE u.email = 'admin@example.com'", User::class.java)
                .resultList.firstOrNull()
                ?: User().apply { email = "admin@example.com"; passwordHash = hash }.also { em.persist(it) }
            // Setup in other test classes can leave more than one admin@example.com behind;
            // login may pick any of them.
            em.createQuery("UPDATE User u SET u.passwordHash = :h, u.isActive = true WHERE u.email = 'admin@example.com'")
                .setParameter("h", hash).executeUpdate()
            val member = em.createQuery("SELECT count(m) FROM Membership m WHERE m.authUser = :u AND m.tenant = :t AND m.role = :r", java.lang.Long::class.java)
                .setParameter("u", user).setParameter("t", root).setParameter("r", adminRole).singleResult.toLong() > 0
            if (!member) em.persist(Membership().apply { authUser = user; tenant = root; role = adminRole; abilities = "[]" })
        }

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
}
