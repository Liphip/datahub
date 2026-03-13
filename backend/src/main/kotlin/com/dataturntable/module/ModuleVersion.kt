package com.dataturntable.module

import com.dataturntable.auth.User
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "module_versions")
class ModuleVersion : PanacheEntityBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    var module: CodeModule? = null
    
    @Column(nullable = false)
    var version: Int = 1
    
    @Column(name = "version_tag")
    var versionTag: String? = null
    
    @Column(columnDefinition = "text", nullable = false)
    var code: String? = null
    
    @Column(columnDefinition = "text")
    var requirements: String = ""
    
    @Column(columnDefinition = "jsonb")
    var config: String = "{}"
    
    @Column(name = "is_active")
    var isActive: Boolean = true
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null
    
    companion object {
        fun findByModuleId(moduleId: UUID): List<ModuleVersion> = 
            list("module.id = ?1 ORDER BY version DESC", moduleId)
        
        fun findByModuleAndVersion(moduleId: UUID, version: Int): ModuleVersion? = 
            find("module.id = ?1 AND version = ?2", moduleId, version).firstResult()
        
        fun findByModuleAndTag(moduleId: UUID, tag: String): ModuleVersion? = 
            find("module.id = ?1 AND versionTag = ?2", moduleId, tag).firstResult()
        
        fun getNextVersion(moduleId: UUID): Int {
            val latest = find("module.id = ?1 ORDER BY version DESC", moduleId).firstResult()
            return (latest?.version ?: 0) + 1
        }
    }
}
