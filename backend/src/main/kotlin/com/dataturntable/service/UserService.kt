package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.UserDto
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import io.quarkus.elytron.security.common.BcryptUtil
import java.util.UUID

@ApplicationScoped
/**
 * Defines the class `UserService`, the contract implemented by this source unit.
 */
class UserService {
    
    private val objectMapper = ObjectMapper()
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getUsersByTenant(tenantId: UUID): List<UserDto> {
        return User.findByTenantId(tenantId).map { toDto(it) }
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getUser(id: UUID): UserDto? {
        val user = User.findById(id) ?: return null
        return toDto(user)
    }
    
    @Transactional
    /**
     * Creates or persists the requested state.
     */
    fun createUser(
        tenantId: UUID,
        email: String,
        password: String,
        firstName: String?,
        lastName: String?,
        roleId: UUID?,
        abilities: List<String>? = null
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
            this.passwordHash = BcryptUtil.bcryptHash(password)
            this.firstName = firstName
            this.lastName = lastName
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
    /**
     * Updates the requested state.
     */
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
    /**
     * Removes, stops, or releases the requested state.
     */
    fun deleteUser(id: UUID): Boolean {
        val user = User.findById(id) ?: return false
        // Delete memberships first
        user.memberships.forEach { it.delete() }
        user.delete()
        return true
    }
    
    @Transactional
    /**
     * Implements `changePassword` for this source unit.
     */
    fun changePassword(id: UUID, oldPassword: String, newPassword: String): Boolean {
        val user = User.findById(id) ?: return false
        
        if (!BcryptUtil.matches(oldPassword, user.passwordHash)) {
            throw SecurityException("Invalid old password")
        }
        
        user.passwordHash = BcryptUtil.bcryptHash(newPassword)
        user.updatedAt = java.time.Instant.now()
        user.persist()
        
        return true
    }
    
    /**
     * Converts a value into the representation required by its caller.
     */
    fun toDto(user: User): UserDto {
         return UserDto(
             id = user.id.toString(),
             email = user.email ?: "",
             firstName = user.firstName,
             lastName = user.lastName,
             tenantId = user.getPrimaryTenant()?.id.toString(),
             roleName = user.getMembershipForTenant(user.getPrimaryTenant()?.id ?: UUID.randomUUID())?.role?.name,
             abilities = user.getAllAbilities().toList(),
             superadmin = user.isSuperAdmin()
         )
    }
}
