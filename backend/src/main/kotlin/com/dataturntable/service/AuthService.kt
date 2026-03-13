package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.jwt.build.Jwt
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.mindrot.jbcrypt.BCrypt
import java.time.Duration
import java.time.Instant
import java.util.*

@ApplicationScoped
class AuthService {
    
    @Inject
    lateinit var tenantService: TenantService
    
    private val objectMapper = ObjectMapper()
    
    @Transactional
    fun login(request: LoginRequest): AuthResponse? {
        val user = if (request.tenantId != null) {
            User.findByTenantAndEmail(UUID.fromString(request.tenantId), request.email)
        } else {
            User.findByEmail(request.email)
        }
        
        if (user == null || !user.isActive) {
            throw SecurityException("Invalid credentials")
        }
        
        if (!BCrypt.checkpw(request.password, user.passwordHash)) {
            throw SecurityException("Invalid credentials")
        }
        
        user.lastLoginAt = Instant.now()
        user.persist()
        
        val token = generateToken(user)
        return AuthResponse(token, toUserDto(user))
    }
    
    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        val user: User
        
        if (request.tenantId != null) {
            val tenant = Tenant.findById(UUID.fromString(request.tenantId))
                ?: throw IllegalArgumentException("Tenant not found")
            
            val existing = User.findByTenantAndEmail(tenant.id!!, request.email)
            if (existing != null) {
                throw IllegalArgumentException("User already exists")
            }
            
            user = User().apply {
                this.tenant = tenant
                this.email = request.email
                this.passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
                this.firstName = request.firstName
                this.lastName = request.lastName
                this.role = Role.findDefaultForTenant(tenant.id!!)
            }
        } else if (request.tenantName != null) {
            val tenant = Tenant().apply {
                name = request.tenantName
            }
            tenant.persist()
            
            user = User().apply {
                this.tenant = tenant
                this.email = request.email
                this.passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
                this.firstName = request.firstName
                this.lastName = request.lastName
                this.isSuperadmin = true
            }
        } else {
            throw IllegalArgumentException("Either tenantId or tenantName is required")
        }
        
        user.persist()
        
        val token = generateToken(user)
        return AuthResponse(token, toUserDto(user))
    }
    
    private fun generateToken(user: User): String {
        val abilities = user.getAllAbilities()
        
        return Jwt.issuer("https://dataturntable.io")
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("tenant_id", user.tenant?.id.toString())
            .claim("abilities", abilities)
            .claim("superadmin", user.isSuperadmin)
            .groups(abilities)
            .expiresIn(Duration.ofHours(24))
            .sign()
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
