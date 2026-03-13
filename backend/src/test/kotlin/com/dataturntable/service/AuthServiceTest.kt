package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.LoginRequest
import com.dataturntable.dto.RegisterRequest
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.mockito.InjectMock
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test
import org.mockito.Mockito

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.ArgumentMatchers.any

@QuarkusTest
class AuthServiceTest {
    
    @Inject
    lateinit var authService: AuthService
    
    @Test
    @Transactional
    fun testLoginWithValidCredentials() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
        }
        tenant.persist()
        
        val role = Role().apply {
            this.tenant = tenant
            name = "Viewer"
            abilities = "[\"module:read\"]"
            isDefault = true
        }
        role.persist()
        
        val user = User().apply {
            this.tenant = tenant
            email = "test@example.com"
            passwordHash = org.mindrot.jbcrypt.BCrypt.hashpw("password", org.mindrot.jbcrypt.BCrypt.gensalt())
            firstName = "Test"
            lastName = "User"
            this.role = role
        }
        user.persist()
        
        // When
        val request = LoginRequest("test@example.com", "password")
        val response = authService.login(request)
        
        // Then
        assertNotNull(response)
        assertNotNull(response.token)
        assertEquals("test@example.com", response.user.email)
    }
    
    @Test
    @Transactional
    fun testLoginWithInvalidPassword() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
        }
        tenant.persist()
        
        val user = User().apply {
            this.tenant = tenant
            email = "test2@example.com"
            passwordHash = org.mindrot.jbcrypt.BCrypt.hashpw("correctpassword", org.mindrot.jbcrypt.BCrypt.gensalt())
        }
        user.persist()
        
        // When & Then
        val request = LoginRequest("test2@example.com", "wrongpassword")
        assertThrows(SecurityException::class.java) {
            authService.login(request)
        }
    }
    
    @Test
    @Transactional
    fun testRegisterNewTenant() {
        // Given
        val request = RegisterRequest(
            email = "newuser@example.com",
            password = "password123",
            tenantName = "New Tenant"
        )
        
        // When
        val response = authService.register(request)
        
        // Then
        assertNotNull(response)
        assertNotNull(response.token)
        assertEquals("newuser@example.com", response.user.email)
        assertTrue(response.user.isSuperadmin)
    }
    
    @Test
    fun testLoginWithNonExistentUser() {
        // Given
        val request = LoginRequest("nonexistent@example.com", "password")
        
        // When & Then
        assertThrows(SecurityException::class.java) {
            authService.login(request)
        }
    }
}
