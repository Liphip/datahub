package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.UserDto
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

@ApplicationScoped
class UserService {
    
    private val objectMapper = ObjectMapper()
    
    fun getUsersByTenant(tenantId: UUID): List<UserDto> {
        return User.findByTenantId(tenantId).map { toDto(it) }
    }
    
    fun getUser(id: UUID): UserDto? {
        val user = User.findById(id) ?: return null
        return toDto(user)
    }
    
    @Transactional
    fun createUser(
        tenantId: UUID,
        email: String,
        password: String,
        firstName: String?,
        lastName: String?,
        roleId: UUID?,
        abilities: List<String>? = null,
        isSuperadmin: Boolean = false
    ): UserDto {
        val tenant = Tenant.findById(tenantId)
            ?: throw IllegalArgumentException("Tenant not found")
        
        val existing = User.findByTenantAndEmail(tenantId, email)
        if (existing != null) {
            throw IllegalArgumentException("User already exists")
        }
        
        val role = if (roleId != null) Role.findById(roleId) else Role.findDefaultForTenant(tenantId)
        
        val user = User().apply {
            this.email = email
            this.passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
            this.firstName = firstName
            this.lastName = lastName
            this.isSuperadmin = isSuperadmin
        }
        user.persist()
        
        // Create membership
        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = role
            this.abilities = if (abilities != null) objectMapper.writeValueAsString(abilities) else "[]"
        }
        membership.persist()
        user.memberships.add(membership)
        
        return toDto(user)
    }
    
    @Transactional
    fun updateUser(
        id: UUID,
        firstName: String?,
        lastName: String?,
        roleId: UUID?,
        abilities: List<String>? = null,
        isActive: Boolean? = null
    ): UserDto? {
        val user = User.findById(id) ?: return null
        
        firstName?.let { user.firstName = it }
        lastName?.let { user.lastName = it }
        
        val primaryTenantId = user.getPrimaryTenant()?.id

        if (roleId != null && primaryTenantId != null) {
            // Update membership role
            val membership = user.getMembershipForTenant(primaryTenantId)
            if (membership != null) {
                membership.role = Role.findById(roleId)
                membership.persist()
            }
        }
        
        abilities?.let {
            if (primaryTenantId != null) {
                // Update membership abilities
                val membership = user.getMembershipForTenant(primaryTenantId)
                if (membership != null) {
                    membership.abilities = objectMapper.writeValueAsString(it)
                    membership.persist()
                }
            }
        }
        
        user.updatedAt = java.time.Instant.now()
        user.persist()
        
        return toDto(user)
    }
    
    @Transactional
    fun deleteUser(id: UUID): Boolean {
        val user = User.findById(id) ?: return false
        // Delete memberships first
        user.memberships.forEach { it.delete() }
        user.delete()
        return true
    }
    
    @Transactional
    fun changePassword(id: UUID, oldPassword: String, newPassword: String): Boolean {
        val user = User.findById(id) ?: return false
        
        if (!BCrypt.checkpw(oldPassword, user.passwordHash)) {
            throw SecurityException("Invalid old password")
        }
        
        user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
        user.updatedAt = java.time.Instant.now()
        user.persist()
        
        return true
    }
    
    fun toDto(user: User): UserDto {
         return UserDto(
             id = user.id.toString(),
             email = user.email ?: "",
             firstName = user.firstName,
             lastName = user.lastName,
             tenantId = user.getPrimaryTenant()?.id.toString(),
             roleName = user.getMembershipForTenant(user.getPrimaryTenant()?.id ?: UUID.randomUUID())?.role?.name,
             abilities = user.getAllAbilities().toList(),
             superadmin = user.isSuperadmin
         )
    }
}
