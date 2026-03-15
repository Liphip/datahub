package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.tenant.Tenant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@ApplicationScoped
class AuthService {
    
    @Inject
    lateinit var tenantService: TenantService
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    private val secureRandom = SecureRandom()
    
    @Transactional
    fun login(request: LoginRequest): AuthResponse? {
        val query = if (request.tenantId != null) {
            em.createQuery("SELECT u FROM User u WHERE u.email = :email AND u.tenant.id = :tenantId", User::class.java)
                .setParameter("email", request.email)
                .setParameter("tenantId", UUID.fromString(request.tenantId))
        } else {
            em.createQuery("SELECT u FROM User u WHERE u.email = :email", User::class.java)
                .setParameter("email", request.email)
        }
        val user = query.resultList.firstOrNull()
        
        if (user == null || !user.isActive) {
            throw SecurityException("Invalid credentials")
        }
        
        if (!BCrypt.checkpw(request.password, user.passwordHash)) {
            throw SecurityException("Invalid credentials")
        }
        
        user.lastLoginAt = java.time.Instant.now()
        user.persist()
        
        val token = generateToken(user)
        return AuthResponse(token, toUserDto(user))
    }
    
    @Transactional
    fun setup(request: SetupRequest): SetupResponse {
        val userCount = em.createQuery("SELECT COUNT(u) FROM User u").getSingleResult() as Long
        if (userCount > 0) {
            throw IllegalStateException("Setup already completed. Users exist in the system.")
        }
        
        val tenant = Tenant().apply {
            name = "Root Tenant"
            settings = "{}"
            quota = """{"max_users": 100, "max_pipelines": 50, "max_storage_mb": 10240}"""
            createdAt = java.time.Instant.now()
            updatedAt = java.time.Instant.now()
        }
        tenant.persist()
        
        val password = generateRandomPassword(16)
        
        val user = User().apply {
            this.tenant = tenant
            this.email = request.email
            this.passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
            this.firstName = "Admin"
            this.lastName = "User"
            this.isSuperadmin = true
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        
        user.persist()
        
        val token = generateToken(user)
        return SetupResponse(
            email = user.email ?: request.email,
            password = password,
            user = toUserDto(user)
        )
    }
    
    private fun generateToken(user: User): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
    
    private fun generateRandomPassword(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"
        return (1..length)
            .map { chars[secureRandom.nextInt(chars.length)] }
            .joinToString("")
    }
    
    private fun toUserDto(user: User): UserDto {
        return UserDto(
            id = user.id.toString(),
            email = user.email ?: "",
            firstName = user.firstName,
            lastName = user.lastName,
            tenantId = user.tenant?.id.toString(),
            roleName = user.role?.name,
            abilities = user.getAllAbilities().toList(),
            isSuperadmin = user.isSuperadmin
        )
    }
    
    fun getCurrentUser(email: String): UserDto? {
        val user = User.findByEmail(email) ?: return null
        return toUserDto(user)
    }
}
