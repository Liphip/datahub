package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.*

@QuarkusTest
class TenantResourceTest {
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    @BeforeEach
    @Transactional
    fun cleanup() {
        em.createQuery("DELETE FROM User").executeUpdate()
        em.createQuery("DELETE FROM Tenant").executeUpdate()
    }
    
    @Test
    fun testListTenantsRequiresAuth() {
        given()
        .`when`()
            .get("/api/internal/v1/tenants")
        .then()
            .statusCode(401)
    }
    
    @Test
    fun testGetTenantNotFound() {
        given()
        .`when`()
            .get("/api/internal/v1/tenants/00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(401)
    }
}
