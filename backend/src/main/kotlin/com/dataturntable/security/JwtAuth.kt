package com.dataturntable.security

import com.dataturntable.auth.User
import com.dataturntable.dto.UserDto
import com.dataturntable.tenant.Membership
import java.util.UUID

object SecurityUtils {
    
    fun toUserDto(user: User): UserDto {
         return UserDto(
             id = user.id.toString(),
             email = user.email ?: "",
             firstName = user.firstName,
             lastName = user.lastName,
             tenantId = user.getPrimaryTenant()?.id.toString(),
             roleName = user.getMembershipForTenant(user.getPrimaryTenant()?.id ?: UUID.fromString("00000000-0000-0000-0000-000000000001"))?.role?.name,
             abilities = user.getAllAbilities().toList(),
             superadmin = user.isSuperadmin
         )
    }
    
    fun hasAbility(user: User?, ability: String): Boolean {
        if (user == null) return false
        return user.hasAbility(ability)
    }
    
    fun canAccessTenant(user: User?, tenantId: String): Boolean {
        if (user == null) return false
        if (user.isSuperadmin) return true
        return user.canAccessTenant(java.util.UUID.fromString(tenantId))
    }
}
