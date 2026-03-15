package com.dataturntable.service

import com.dataturntable.dto.TenantDto
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TenantService {
    
    fun getTenantTree(): List<TenantDto> {
        return emptyList()
    }
}
