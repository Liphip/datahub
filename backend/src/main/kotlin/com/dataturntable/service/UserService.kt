package com.dataturntable.service

import com.dataturntable.dto.UserDto
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class UserService {
    
    fun getUsersByTenant(tenantId: UUID): List<UserDto> {
        return emptyList()
    }
}
