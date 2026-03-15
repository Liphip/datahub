package com.dataturntable.module

import com.dataturntable.auth.User
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.EntityManager
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
        fun findById(id: UUID): ModuleVersion? = 
            PanacheEntityBase.find<ModuleVersion>("id = ?1", id).firstResult()
        
        fun findByModuleId(moduleId: UUID): List<ModuleVersion> = 
            PanacheEntityBase.list<ModuleVersion>("module.id = ?1 ORDER BY version DESC", moduleId)
        
        fun findByModuleAndVersion(moduleId: UUID, version: Int): ModuleVersion? = 
            PanacheEntityBase.find<ModuleVersion>("module.id = ?1 AND version = ?2", moduleId, version).firstResult()
        
        fun findByModuleAndTag(moduleId: UUID, tag: String): ModuleVersion? = 
            PanacheEntityBase.find<ModuleVersion>("module.id = ?1 AND versionTag = ?2", moduleId, tag).firstResult()
        
        fun getNextVersion(moduleId: UUID): Int {
            val query = getEntityManager().createQuery("SELECT m FROM ModuleVersion m WHERE m.module.id = :mid ORDER BY m.version DESC", ModuleVersion::class.java)
            query.setParameter("mid", moduleId)
            val latest = query.resultList.firstOrNull()
            return (latest?.version ?: 0) + 1
        }
    }
}
