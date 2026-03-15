package com.dataturntable.service

import com.dataturntable.dto.*
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ModuleService {
    
    fun getModules(tenantId: UUID): List<CodeModuleDto> {
        return emptyList()
    }
    
    fun getModulesByType(tenantId: UUID, type: com.dataturntable.module.ModuleType): List<CodeModuleDto> {
        return emptyList()
    }
}
