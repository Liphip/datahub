package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Tenant
import io.smallrye.jwt.build.Jwt
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.jwt.JsonWebToken
import org.mindrot.jbcrypt.BCrypt
import de.mkammerer.argon2.Argon2Factory
import java.security.SecureRandom
import java.util.HashSet
import java.util.UUID

@ApplicationScoped
/**
 * Defines the class `AuthService`, the contract implemented by this source unit.
 */
class AuthService {
    
    @Inject
    lateinit var tenantService: TenantService
    
    @Inject
    lateinit var jwt: JsonWebToken
    
    @PersistenceContext
    lateinit var em: EntityManager
    
    private val secureRandom = SecureRandom()
    
    @Transactional
    /**
     * Implements `login` for this source unit.
     */
    fun login(request: LoginRequest): AuthResponse? {
        val user = findUserByEmail(request.email, request.tenantId)
        
        if (user == null || !user.isActive) {
            throw SecurityException("Invalid credentials")
        }
        
        val rawPassword = request.password?.takeIf { it.isNotBlank() }
        val legacyPasswordHash = request.passwordHash?.takeIf { it.isNotBlank() }
        val valid = when {
            rawPassword != null && user.passwordHash?.startsWith("\$argon2id\$") == true -> safeVerifyArgon2(rawPassword, user.passwordHash!!)
            rawPassword != null -> legacyPasswordCandidates(rawPassword).any { safeBCryptCheck(it, user.passwordHash) }
            legacyPasswordHash != null && user.passwordHash?.startsWith("\$argon2id\$") == true -> safeVerifyArgon2(legacyPasswordHash, user.passwordHash!!)
            legacyPasswordHash != null -> safeBCryptCheck(legacyPasswordHash, user.passwordHash)
            else -> false
        }
        if (!valid) {
            throw SecurityException("Invalid credentials")
        }
        if (rawPassword != null && user.passwordHash?.startsWith("\$argon2id\$") != true) {
            user.passwordHash = hashArgon2(rawPassword)
        }
        
        user.lastLoginAt = java.time.Instant.now()
        user.persist()
        
        val requestedTenantId = parseUuid(request.tenantId)
        if (requestedTenantId != null && !user.canAccessTenant(requestedTenantId)) {
            throw SecurityException("Invalid tenant context")
        }

        val token = generateJwtToken(user, requestedTenantId)
        return AuthResponse(token, toUserDto(user, requestedTenantId))
    }

    @Transactional
    /**
     * Implements `switchTenant` for this source unit.
     */
    fun switchTenant(userId: UUID, tenantId: String): AuthResponse {
        val tenantUuid = parseUuid(tenantId) ?: throw IllegalArgumentException("Invalid tenantId")
        val user = em.find(User::class.java, userId) ?: throw SecurityException("Unauthorized")
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            throw NotFoundException("Tenant not found")
        }
        if (!user.isSuperAdmin() && user.getMembershipForTenant(tenantUuid) == null) {
            throw SecurityException("Forbidden")
        }

        val token = generateJwtToken(user, tenantUuid)
        return AuthResponse(token, toUserDto(user, tenantUuid))
    }

    @Transactional
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getTenantContexts(userId: UUID): List<TenantContextDto> {
        val memberships = em.createQuery(
            "SELECT m FROM Membership m JOIN FETCH m.tenant t LEFT JOIN FETCH m.role r WHERE m.authUser.id = :userId",
            Membership::class.java
        )
            .setParameter("userId", userId)
            .resultList

        return memberships.mapNotNull { membership ->
            val tenant = membership.tenant ?: return@mapNotNull null
            val tenantId = tenant.id ?: return@mapNotNull null
            TenantContextDto(
                tenantId = tenantId.toString(),
                tenantName = tenant.name ?: "Unnamed Tenant",
                roleName = membership.role?.name
            )
        }
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
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
    /**
     * Updates the requested state.
     */
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
        // passwordHash is a deprecated transport alias retained for existing setup clients.
        // It is treated as an opaque credential and is still stored only as Argon2id.
        val password = request.password?.takeIf { it.isNotBlank() }
            ?: request.passwordHash?.takeIf { it.isNotBlank() }
            ?: generatedPassword
        val responsePassword = when {
            !request.password.isNullOrBlank() -> request.password
            !request.passwordHash.isNullOrBlank() -> request.passwordHash
            else -> generatedPassword
        }
        
        val user = User().apply {
            this.email = request.email
            this.passwordHash = hashArgon2(password)
            this.firstName = "Admin"
            this.lastName = "User"
            this.isActive = true
            this.isSuperadmin = true
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
            password = responsePassword,
            user = toUserDto(user)
        )
    }

    /**
     * Evaluates the named capability or state predicate.
     */
    private fun legacyPasswordCandidates(password: String): List<String> {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return listOf(password, java.util.Base64.getEncoder().encodeToString(hash))
    }

    private fun safeBCryptCheck(candidate: String, storedHash: String?): Boolean =
        try {
            storedHash != null && BCrypt.checkpw(candidate, storedHash)
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun hashArgon2(password: String): String =
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(3, 65536, 1, password.toCharArray())

    private fun verifyArgon2(password: String, encodedHash: String): Boolean =
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).verify(encodedHash, password.toCharArray())

    private fun safeVerifyArgon2(password: String, encodedHash: String): Boolean =
        try {
            verifyArgon2(password, encodedHash)
        } catch (e: Exception) {
            false
        }
    
    /**
     * Implements `generateJwtToken` for this source unit.
     */
    private fun generateJwtToken(user: User, tenantContextId: UUID? = null): String {
        val groups = HashSet<String>()
        if (user.isSuperAdmin()) groups.add("superadmin")

        // Prefer a root-tenant membership if present (superadmin scope), otherwise pick the first membership
        val primaryMembership = user.loadMemberships().firstOrNull { it.tenant?.parent == null }
            ?: user.loadMemberships().firstOrNull()

        val contextMembership = tenantContextId?.let { user.getMembershipForTenant(it) }

        (contextMembership?.role?.name ?: primaryMembership?.role?.name)?.let { groups.add(it) }

        val tenantId = tenantContextId?.toString() ?: primaryMembership?.tenant?.id?.toString()

        val jwtBuilder = Jwt.issuer("https://dataturntable.io")
            .subject(user.id.toString())
            .upn(user.email)
            .groups(groups)
            .claim("email", user.email)
            .claim("authorizationVersion", user.authorizationVersion)
            .expiresIn(3600L)
        if (!tenantId.isNullOrBlank()) {
            jwtBuilder.claim("tenantId", tenantId)
        }

        return jwtBuilder.sign()
    }
    
    /**
     * Implements `generateRandomPassword` for this source unit.
     */
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
    
    /**
     * Converts a value into the representation required by its caller.
     */
    private fun toUserDto(user: User, tenantContextId: UUID? = null): UserDto {
         val contextTenant = tenantContextId?.let { em.find(Tenant::class.java, it) }
         val primaryTenant = contextTenant ?: user.getPrimaryTenant()
         return UserDto(
             id = user.id.toString(),
             email = user.email ?: "",
             firstName = user.firstName,
             lastName = user.lastName,
             tenantId = primaryTenant?.id?.toString(),
             roleName = primaryTenant?.id?.let { user.getMembershipForTenant(it)?.role?.name },
             abilities = user.getAllAbilities().toList(),
             superadmin = user.isSuperAdmin()
         )
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getCurrentUser(email: String): UserDto? {
        val user = User.findByEmail(email) ?: return null
        return toUserDto(user)
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getUserFromJwt(): UserDto? {
        val subject = jwt.subject ?: return null
        val userId = try {
            UUID.fromString(subject)
        } catch (e: Exception) {
            return null
        }
        val user = em.find(User::class.java, userId) ?: return null
        val tenantIdFromJwt = parseUuid(jwt.getClaim("tenantId") as? String)
        return toUserDto(user, tenantIdFromJwt)
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }
}
