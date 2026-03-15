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
    
    fun getLatestVersion(): ModuleVersion? = 
        versions.firstOrNull()
    
    fun getActiveVersion(): ModuleVersion? = 
        versions.firstOrNull { it.isActive }
}
