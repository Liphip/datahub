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
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.util.Base64

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class AuthServiceTest {
    
    @Inject
    lateinit var authService: AuthService
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    @BeforeEach
    @Transactional
    fun cleanup() {
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

    @AfterEach
    @Transactional
    fun cleanupAfter() = cleanup()
    
    @Test
    @Transactional
    fun testSetupCreatesInitialUser() {
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
    @Transactional
    fun testSetupFailsWhenUsersExist() {
        val request = SetupRequest(email = "admin@example.com")
        authService.setup(request)
        
        val secondRequest = SetupRequest(email = "another@example.com")
        assertThrows(IllegalStateException::class.java) {
            authService.setup(secondRequest)
        }
    }
    
    @Test
    @Transactional
    fun testLoginWithValidCredentials() {
        val setupRequest = SetupRequest(email = "admin@example.com")
        val setupResponse = authService.setup(setupRequest)
        
        val loginRequest = LoginRequest(
            email = "admin@example.com",
            password = setupResponse.password
        )
        
        val response = authService.login(loginRequest)
        
        assertNotNull(response)
        assertNotNull(response?.token)
        assertNotNull(response?.user)
        assertEquals("admin@example.com", response?.user?.email)
    }
    
    @Test
    @Transactional
    fun testLoginWithInvalidPassword() {
        val setupRequest = SetupRequest(email = "admin@example.com")
        authService.setup(setupRequest)
        
        val loginRequest = LoginRequest(
            email = "admin@example.com",
            password = "wrongpassword"
        )
        
        assertThrows(SecurityException::class.java) {
            authService.login(loginRequest)
        }
    }
    
    @Test
    @Transactional
    fun testLoginWithNonexistentUser() {
        val loginRequest = LoginRequest(
            email = "nonexistent@example.com",
            password = "some-password"
        )
        
        assertThrows(SecurityException::class.java) {
            authService.login(loginRequest)
        }
    }
    
    @Test
    @Transactional
    fun testLoginGeneratesValidJwt() {
        val setupRequest = SetupRequest(email = "admin@example.com")
        val setupResponse = authService.setup(setupRequest)
        
        val loginRequest = LoginRequest(
            email = "admin@example.com",
            password = setupResponse.password
        )
        
        val response = authService.login(loginRequest)
        
        assertNotNull(response?.token)
        assertTrue(response?.token?.split(".")?.size == 3)
    }
    
    @Test
    @Transactional
    fun testSetupCreatesRootTenant() {
        val request = SetupRequest(email = "admin@example.com")
        val response = authService.setup(request)
        
        assertNotNull(response.user.tenantId)
        
        val tenant = em.find(Tenant::class.java, java.util.UUID.fromString(response.user.tenantId))
        assertNotNull(tenant)
        assertEquals("Root Tenant", tenant.name)
    }
    
    @Test
    @Transactional
    fun testSetupGeneratesStrongPassword() {
        val request = SetupRequest(email = "admin@example.com")
        val response = authService.setup(request)
        
        val password = response.password
        assertTrue(password.length >= 12)
        assertTrue(password.any { it.isUpperCase() })
        assertTrue(password.any { it.isLowerCase() })
        assertTrue(password.any { it.isDigit() })
        assertTrue(password.any { !it.isLetterOrDigit() })
    }
    
    @Test
    @Transactional
    fun `legacy BCrypt SHA-256 password migrates to Argon2id on login`() {
        val password = "LegacyPass!123"
        val user = User().apply {
            email = "legacy@example.com"
            passwordHash = BCrypt.hashpw(hashPassword(password), BCrypt.gensalt())
            isActive = true
        }
        em.persist(user)
        em.flush()

        authService.login(LoginRequest(email = user.email!!, password = password))

        assertTrue(user.passwordHash!!.startsWith("\$argon2id\$"))
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }
}
