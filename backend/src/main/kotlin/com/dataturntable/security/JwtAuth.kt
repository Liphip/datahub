package com.dataturntable.security

import com.dataturntable.auth.User
import com.dataturntable.dto.UserDto
import java.util.UUID

object SecurityUtils {
    
    fun toUserDto(user: User): UserDto {
         val primaryTenant = user.getPrimaryTenant()
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
    
    fun hasAbility(user: User?, ability: String): Boolean {
        if (user == null) return false
        return user.hasAbility(ability)
    }
    
    fun canAccessTenant(user: User?, tenantId: String): Boolean {
        if (user == null) return false
        if (user.isSuperAdmin()) return true
        val tenantUuid = try {
            UUID.fromString(tenantId)
        } catch (_: Exception) {
            return false
        }
        return user.canAccessTenant(tenantUuid)
    }
}
