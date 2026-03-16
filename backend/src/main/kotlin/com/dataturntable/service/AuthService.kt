package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.tenant.Tenant
import io.smallrye.jwt.build.Jwt
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.jwt.JsonWebToken
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.HashSet
import java.util.UUID

@ApplicationScoped
class AuthService {
    
    @Inject
    lateinit var tenantService: TenantService
    
    @Inject
    lateinit var jwt: JsonWebToken
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    private val secureRandom = SecureRandom()
    
    @Transactional
    fun login(request: LoginRequest): AuthResponse? {
        val user = findUserByEmail(request.email, request.tenantId)
        
        if (user == null || !user.isActive) {
            throw SecurityException("Invalid credentials")
        }
        
        val candidateHashes = normalizeClientPasswordHash(request.passwordHash)
        val valid = candidateHashes.any { BCrypt.checkpw(it, user.passwordHash) }
        if (!valid) {
            throw SecurityException("Invalid credentials")
        }
        
        user.lastLoginAt = java.time.Instant.now()
        user.persist()
        
        val token = generateJwtToken(user)
        return AuthResponse(token, toUserDto(user))
    }
    
    private fun findUserByEmail(email: String, tenantId: String?): User? {
        val query = if (tenantId != null) {
            // Find a user with the given email that has a membership in the requested tenant
            em.createQuery(
                "SELECT u FROM User u JOIN u.memberships m WHERE u.email = :email AND m.tenant.id = :tenantId",
                User::class.java
            )
                .setParameter("email", email)
                .setParameter("tenantId", UUID.fromString(tenantId))
        } else {
            em.createQuery("SELECT u FROM User u WHERE u.email = :email", User::class.java)
                .setParameter("email", email)
        }
        return query.resultList.firstOrNull()
    }
    
    @Transactional
    fun setup(request: SetupRequest): SetupResponse {
        val userCount = em.createQuery("SELECT count(u) FROM User u").singleResult as Long
        if (userCount > 0) {
            throw IllegalStateException("Setup already completed. Users exist in the system.")
        }
        
        val tenant = Tenant().apply {
            name = request.tenantName
            settings = "{}"
            quota = """{"max_users": 100, "max_pipelines": 50, "max_storage_mb": 10240}"""
            createdAt = java.time.Instant.now()
            updatedAt = java.time.Instant.now()
        }
        em.persist(tenant)
        
        // Create default roles for the root tenant
        tenantService.createDefaultRoles(tenant)
        em.flush()
        
        val generatedPassword = generateRandomPassword(16)
        val submittedHash = request.passwordHash ?: request.password
        val passwordHash = submittedHash ?: hashPasswordForStorage(generatedPassword)
        
        val user = User().apply {
            this.email = request.email
            this.passwordHash = BCrypt.hashpw(passwordHash, BCrypt.gensalt())
            this.firstName = "Admin"
            this.lastName = "User"
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        
        user.persist()
        em.flush()
        
        // Assign Tenant Admin membership for the root tenant
        val adminRole = em.createQuery(
            "SELECT r FROM Role r WHERE r.tenant.id = :tenantId AND r.name = :roleName",
            com.dataturntable.tenant.Role::class.java
        )
            .setParameter("tenantId", tenant.id)
            .setParameter("roleName", "Tenant Admin")
            .resultList
            .firstOrNull()
        val membership = com.dataturntable.tenant.Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = adminRole
            this.abilities = "[]"
            this.createdAt = java.time.Instant.now()
        }
        membership.persist()
        user.memberships.add(membership)

        return SetupResponse(
            email = user.email ?: request.email,
            password = generatedPassword,
            user = toUserDto(user)
        )
    }

    private fun hashPasswordForStorage(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return java.util.Base64.getEncoder().encodeToString(hash)
    }

    private fun normalizeClientPasswordHash(passwordHash: String): List<String> {
        val normalized = mutableSetOf(passwordHash)
        if (passwordHash.length == 64 && passwordHash.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            val bytes = ByteArray(passwordHash.length / 2)
            var i = 0
            while (i < passwordHash.length) {
                bytes[i / 2] = passwordHash.substring(i, i + 2).toInt(16).toByte()
                i += 2
            }
            normalized.add(java.util.Base64.getEncoder().encodeToString(bytes))
        }
        return normalized.toList()
    }
    
    private fun generateJwtToken(user: User): String {
        val groups = HashSet<String>()
        if (user.isSuperAdmin()) groups.add("superadmin")

        // Prefer a root-tenant membership if present (superadmin scope), otherwise pick the first membership
        val primaryMembership = user.loadMemberships().firstOrNull { it.tenant?.parent == null }
            ?: user.loadMemberships().firstOrNull()

        primaryMembership?.role?.name?.let { groups.add(it) }

        val tenantId = primaryMembership?.tenant?.id?.toString()

        return Jwt.issuer("https://dataturntable.io")
            .subject(user.id.toString())
            .upn(user.email)
            .groups(groups)
            .claim("tenantId", tenantId)
            .claim("email", user.email)
            .expiresIn(3600L)
            .sign()
    }
    
    private fun generateRandomPassword(length: Int): String {
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = "abcdefghijklmnopqrstuvwxyz"
        val digits = "0123456789"
        val special = "!@#\$%^&*"
        
        val password = StringBuilder()
        password.append(upper[secureRandom.nextInt(upper.length)])
        password.append(lower[secureRandom.nextInt(lower.length)])
        password.append(digits[secureRandom.nextInt(digits.length)])
        password.append(special[secureRandom.nextInt(special.length)])
        
        val allChars = upper + lower + digits + special
        for (i in 4 until length) {
            password.append(allChars[secureRandom.nextInt(allChars.length)])
        }
        
        return password.toString().toList().shuffled(secureRandom).joinToString("")
    }
    
    private fun toUserDto(user: User): UserDto {
         val primaryTenant = user.getPrimaryTenant()
         return UserDto(
             id = user.id.toString(),
             email = user.email ?: "",
             firstName = user.firstName,
             lastName = user.lastName,
             tenantId = primaryTenant?.id.toString(),
             roleName = user.getMembershipForTenant(primaryTenant?.id ?: UUID.randomUUID())?.role?.name,
             abilities = user.getAllAbilities().toList(),
             superadmin = user.isSuperAdmin()
         )
    }
    
    fun getCurrentUser(email: String): UserDto? {
        val user = User.findByEmail(email) ?: return null
        return toUserDto(user)
    }
    
    fun getUserFromJwt(): UserDto? {
        val subject = jwt.subject ?: return null
        val userId = try {
            UUID.fromString(subject)
        } catch (e: Exception) {
            return null
        }
        val user = em.find(User::class.java, userId) ?: return null
        return toUserDto(user)
    }
}
