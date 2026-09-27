package com.dataturntable.service

import com.dataturntable.auth.ApiKey
import com.dataturntable.auth.User
import com.dataturntable.dto.LoginRequest
import com.dataturntable.dto.SetupRequest
import com.dataturntable.tenant.Tenant
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import io.quarkus.test.TestTransaction
import org.junit.jupiter.api.Test

import java.util.UUID

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class AuthServiceTest {
    
    @Inject
    lateinit var authService: AuthService
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    /** Setup requires an empty user table; every test rolls back, so other tests keep their users. */
    private fun withoutUsers() {
        em.createQuery("DELETE FROM Membership").executeUpdate()
        em.createQuery("DELETE FROM User").executeUpdate()
    }

    @Test
    @TestTransaction
    fun testSetupCreatesInitialUser() {
        withoutUsers()
        val request = SetupRequest(email = "admin@example.com")
        val response = authService.setup(request)
        
        assertNotNull(response)
        assertEquals("admin@example.com", response.email)
        assertNotNull(response.password)
        assertTrue(response.password.length >= 8)
        assertNotNull(response.user)
        assertEquals("admin@example.com", response.user.email)
        assertTrue(response.user.superadmin)
        assertTrue(response.user.abilities.contains("*"))
    }
    
    @Test
    @TestTransaction
    fun testSetupFailsWhenUsersExist() {
        withoutUsers()
        val request = SetupRequest(email = "admin@example.com")
        authService.setup(request)
        
        val secondRequest = SetupRequest(email = "another@example.com")
        assertThrows(IllegalStateException::class.java) {
            authService.setup(secondRequest)
        }
    }
    
    @Test
    @TestTransaction
    fun testLoginWithValidCredentials() {
        withoutUsers()
        val setupRequest = SetupRequest(email = "admin@example.com")
        val setupResponse = authService.setup(setupRequest)
        
        val password = setupResponse.password
        
        val loginRequest = LoginRequest(
            email = "admin@example.com",
            password = password
        )
        
        val response = authService.login(loginRequest)
        
        assertNotNull(response)
        assertNotNull(response?.token)
        assertNotNull(response?.user)
        assertEquals("admin@example.com", response?.user?.email)
    }
    
    @Test
    @TestTransaction
    fun testLoginWithInvalidPasswordHash() {
        withoutUsers()
        val setupRequest = SetupRequest(email = "admin@example.com")
        authService.setup(setupRequest)
        
        // Backend double-hashes, so wrong hash should fail
        val wrongHash = "wrongpassword"
        
        val loginRequest = LoginRequest(
            email = "admin@example.com",
            password = wrongHash
        )
        
        assertThrows(SecurityException::class.java) {
            authService.login(loginRequest)
        }
    }
    
    @Test
    @TestTransaction
    fun testLoginWithNonexistentUser() {
        withoutUsers()
        val loginRequest = LoginRequest(
            email = "nonexistent@example.com",
            password = "somehash"
        )
        
        assertThrows(SecurityException::class.java) {
            authService.login(loginRequest)
        }
    }
    
    @Test
    @TestTransaction
    fun testLoginGeneratesValidJwt() {
        withoutUsers()
        val setupRequest = SetupRequest(email = "admin@example.com")
        val setupResponse = authService.setup(setupRequest)
        
        val password = setupResponse.password
        
        val loginRequest = LoginRequest(
            email = "admin@example.com",
            password = password
        )
        
        val response = authService.login(loginRequest)
        
        assertNotNull(response?.token)
        assertTrue(response?.token?.split(".")?.size == 3)
    }
    
    @Test
    @TestTransaction
    fun testSetupCreatesRootTenant() {
        withoutUsers()
        val request = SetupRequest(email = "admin@example.com")
        val response = authService.setup(request)
        
        assertNotNull(response.user.tenantId)
        
        val tenant = em.find(Tenant::class.java, java.util.UUID.fromString(response.user.tenantId))
        assertNotNull(tenant)
        assertEquals("Root Tenant", tenant.name)
    }
    
    @Test
    @TestTransaction
    fun testSetupGeneratesStrongPassword() {
        withoutUsers()
        val request = SetupRequest(email = "admin@example.com")
        val response = authService.setup(request)
        
        val password = response.password
        assertTrue(password.length >= 12)
        assertTrue(password.any { it.isUpperCase() })
        assertTrue(password.any { it.isLowerCase() })
        assertTrue(password.any { it.isDigit() })
        assertTrue(password.any { !it.isLetterOrDigit() })
    }
    
}
