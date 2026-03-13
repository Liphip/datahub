package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.UserDto
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.mindrot.jbcrypt.BCrypt
import java.util.*

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
            this.tenant = tenant
            this.email = email
            this.passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
            this.firstName = firstName
            this.lastName = lastName
            this.role = role
            this.abilities = if (abilities != null) objectMapper.writeValueAsString(abilities) else "[]"
            this.isSuperadmin = isSuperadmin
        }
        
        user.persist()
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
        isActive?.let { user.isActive = it }
        
        if (roleId != null) {
            user.role = Role.findById(roleId)
        }
        
        abilities?.let { 
            user.abilities = objectMapper.writeValueAsString(it)
        }
        
        user.updatedAt = java.time.Instant.now()
        user.persist()
        
        return toDto(user)
    }
    
    @Transactional
    fun deleteUser(id: UUID): Boolean {
        val user = User.findById(id) ?: return false
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
            tenantId = user.tenant?.id.toString(),
            roleName = user.role?.name,
            abilities = user.getAllAbilities().toList(),
            isSuperadmin = user.isSuperadmin
        )
    }
}
