package com.dataturntable.service

import com.dataturntable.dto.*
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64

@ApplicationScoped
class AuthService {
    
    fun login(request: LoginRequest): AuthResponse {
        val token = generateStubToken()
        return AuthResponse(token, UserDto(
            id = "stub-id",
            email = request.email,
            firstName = "Stub",
            lastName = "User",
            tenantId = null,
            roleName = "admin",
            abilities = listOf("*"),
            isSuperadmin = false
        ))
    }
    
    fun register(request: RegisterRequest): AuthResponse {
        val token = generateStubToken()
        return AuthResponse(token, UserDto(
            id = "stub-id",
            email = request.email,
            firstName = request.firstName ?: "Stub",
            lastName = request.lastName ?: "User",
            tenantId = null,
            roleName = "admin",
            abilities = listOf("*"),
            isSuperadmin = false
        ))
    }
    
    fun getCurrentUser(email: String): UserDto? {
        return UserDto(
            id = "stub-id",
            email = email,
            firstName = "Stub",
            lastName = "User",
            tenantId = null,
            roleName = "admin",
            abilities = listOf("*"),
            isSuperadmin = false
        )
    }
    
    fun needsSetup(): Boolean {
        return true
    }
    
    private fun generateStubToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
