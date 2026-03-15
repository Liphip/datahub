package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.*

@ApplicationScoped
class ModuleService {
    
    @Inject
    lateinit var tenantService: TenantService
    
    private val objectMapper = ObjectMapper()
    
    fun getModules(tenantId: UUID): List<CodeModuleDto> {
        return CodeModule.findByTenantId(tenantId).map { toDto(it) }
    }
    
    fun getModulesByType(tenantId: UUID, type: ModuleType): List<CodeModuleDto> {
        return CodeModule.findByTenantAndType(tenantId, type).map { toDto(it) }
    }
    
    fun getModule(id: UUID): CodeModuleDto? {
        val module = CodeModule.findById(id) ?: return null
        return toDto(module)
    }
    
    @Transactional
    fun createModule(tenantId: UUID, request: CreateModuleRequest, user: User): CodeModuleDto {
        val tenant = Tenant.findById(tenantId)
            ?: throw IllegalArgumentException("Tenant not found")
        
        val existing = CodeModule.findByTenantAndName(tenantId, request.name, request.moduleType)
        if (existing != null) {
            throw IllegalArgumentException("Module already exists")
        }
        
        val module = CodeModule().apply {
            this.tenant = tenant
            this.name = request.name
            this.description = request.description
            this.moduleType = request.moduleType
            this.createdBy = user
        }
        
        module.persist()
        return toDto(module)
    }
    
    @Transactional
    fun deleteModule(id: UUID): Boolean {
        val module = CodeModule.findById(id) ?: return false
        module.delete()
        return true
    }
    
    fun getVersions(moduleId: UUID): List<ModuleVersionDto> {
        return ModuleVersion.findByModuleId(moduleId).map { toVersionDto(it) }
    }
    
    fun getVersion(moduleId: UUID, version: Int): ModuleVersionDto? {
        val version = ModuleVersion.findByModuleAndVersion(moduleId, version) ?: return null
        return toVersionDto(version)
    }
    
    fun getVersionByTag(moduleId: UUID, tag: String): ModuleVersionDto? {
        val version = ModuleVersion.findByModuleAndTag(moduleId, tag) ?: return null
        return toVersionDto(version)
    }
    
    @Transactional
    fun createVersion(moduleId: UUID, request: CreateModuleVersionRequest, user: User): ModuleVersionDto {
        val module = CodeModule.findById(moduleId)
            ?: throw IllegalArgumentException("Module not found")
        
        val nextVersion = ModuleVersion.getNextVersion(moduleId)
        
        val version = ModuleVersion().apply {
            this.module = module
            this.version = nextVersion
            this.versionTag = request.versionTag
            this.code = request.code
            this.requirements = request.requirements
            this.config = objectMapper.writeValueAsString(request.config)
            this.createdBy = user
        }
        
        version.persist()
        return toVersionDto(version)
    }
    
    @Transactional
    fun tagVersion(moduleId: UUID, version: Int, tag: String): ModuleVersionDto? {
        val version = ModuleVersion.findByModuleAndVersion(moduleId, version) ?: return null
        version.versionTag = tag
        version.persist()
        return toVersionDto(version)
    }
    
    fun toDto(module: CodeModule): CodeModuleDto {
        val versions = module.versions.map { toVersionDto(it) }
        
        return CodeModuleDto(
            id = module.id.toString(),
            tenantId = module.tenant?.id.toString(),
            name = module.name ?: "",
            description = module.description,
            moduleType = module.moduleType ?: ModuleType.PROCESSING,
            isActive = module.isActive,
            createdAt = module.createdAt,
            versions = versions
        )
    }
    
    fun toVersionDto(version: ModuleVersion): ModuleVersionDto {
        val config = try {
            objectMapper.readValue(version.config, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
        
        return ModuleVersionDto(
            id = version.id.toString(),
            moduleId = version.module?.id.toString(),
            version = version.version,
            versionTag = version.versionTag,
            code = version.code ?: "",
            requirements = version.requirements ?: "",
            config = config,
            isActive = version.isActive,
            createdAt = version.createdAt
        )
    }
}
