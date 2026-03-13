package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.CreateModuleRequest
import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleType
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

@QuarkusTest
class ModuleServiceTest {
    
    @Inject
    lateinit var moduleService: ModuleService
    
    @Test
    @Transactional
    fun testCreateModule() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant"
        }
        tenant.persist()
        
        val user = User().apply {
            this.tenant = tenant
            email = "test@example.com"
        }
        user.persist()
        
        val request = CreateModuleRequest(
            name = "Test Module",
            moduleType = ModuleType.PROCESSING,
            description = "A test module"
        )
        
        // When
        val module = moduleService.createModule(tenant.id!!, request, user)
        
        // Then
        assertNotNull(module)
        assertEquals("Test Module", module.name)
        assertEquals(ModuleType.PROCESSING, module.moduleType)
    }
    
    @Test
    @Transactional
    fun testGetModules() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant 2"
        }
        tenant.persist()
        
        val module = CodeModule().apply {
            this.tenant = tenant
            name = "Test Module 2"
            moduleType = ModuleType.IMPORT
        }
        module.persist()
        
        // When
        val modules = moduleService.getModules(tenant.id!!)
        
        // Then
        assertNotNull(modules)
        assertTrue(modules.isNotEmpty())
    }
    
    @Test
    @Transactional
    fun testCreateModuleVersion() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant 3"
        }
        tenant.persist()
        
        val user = User().apply {
            this.tenant = tenant
            email = "test3@example.com"
        }
        user.persist()
        
        val codeModule = CodeModule().apply {
            this.tenant = tenant
            name = "Test Module 3"
            moduleType = ModuleType.PROCESSING
        }
        codeModule.persist()
        
        val request = com.dataturntable.dto.CreateModuleVersionRequest(
            code = "def process(data): return data",
            requirements = "pandas\nnumpy",
            versionTag = "v1.0"
        )
        
        // When
        val version = moduleService.createVersion(codeModule.id!!, request, user)
        
        // Then
        assertNotNull(version)
        assertEquals(1, version.version)
        assertEquals("v1.0", version.versionTag)
    }
    
    @Test
    @Transactional
    fun testModuleVersioning() {
        // Given
        val tenant = Tenant().apply {
            name = "Test Tenant 4"
        }
        tenant.persist()
        
        val user = User().apply {
            this.tenant = tenant
            email = "test4@example.com"
        }
        user.persist()
        
        val codeModule = CodeModule().apply {
            this.tenant = tenant
            name = "Test Module 4"
            moduleType = ModuleType.EXPORT
        }
        codeModule.persist()
        
        // Create first version
        val request1 = com.dataturntable.dto.CreateModuleVersionRequest(
            code = "version 1",
            versionTag = "v1.0"
        )
        moduleService.createVersion(codeModule.id!!, request1, user)
        
        // Create second version
        val request2 = com.dataturntable.dto.CreateModuleVersionRequest(
            code = "version 2",
            versionTag = "v2.0"
        )
        moduleService.createVersion(codeModule.id!!, request2, user)
        
        // When
        val versions = moduleService.getVersions(codeModule.id!!)
        
        // Then
        assertEquals(2, versions.size)
        assertEquals(2, versions[0].version) // Latest first
        assertEquals(1, versions[1].version)
    }
}
