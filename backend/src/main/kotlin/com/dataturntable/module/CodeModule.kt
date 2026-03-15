package com.dataturntable.module

import com.dataturntable.auth.User
import com.dataturntable.tenant.Tenant
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ModuleType {
    IMPORT, PROCESSING, EXPORT
}

@Entity
@Table(name = "code_modules")
class CodeModule : PanacheEntityBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @Column(nullable = false)
    var name: String? = null
    
    @Column
    var description: String? = null
    
    @Enumerated(EnumType.STRING)
    @Column(name = "module_type", nullable = false)
    var moduleType: ModuleType? = null
    
    @Column(name = "is_active")
    var isActive: Boolean = true
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null
    
    @OneToMany(mappedBy = "module", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @OrderBy("version DESC")
    var versions: MutableList<ModuleVersion> = mutableListOf()
    
    companion object {
        fun findById(id: UUID): CodeModule? = 
            PanacheEntityBase.find<CodeModule>("id = ?1", id).firstResult()
        
        fun findByTenantId(tenantId: UUID): List<CodeModule> = 
            PanacheEntityBase.list<CodeModule>("tenant.id = ?1", tenantId)
        
        fun findByTenantAndType(tenantId: UUID, type: ModuleType): List<CodeModule> = 
            PanacheEntityBase.list<CodeModule>("tenant.id = ?1 AND moduleType = ?2", tenantId, type)
        
        fun findByTenantAndName(tenantId: UUID, name: String, type: ModuleType): CodeModule? = 
            PanacheEntityBase.find<CodeModule>("tenant.id = ?1 AND name = ?2 AND moduleType = ?3", tenantId, name, type).firstResult()
    }
    
    fun getLatestVersion(): ModuleVersion? = 
        versions.firstOrNull()
    
    fun getActiveVersion(): ModuleVersion? = 
        versions.firstOrNull { it.isActive }
}
