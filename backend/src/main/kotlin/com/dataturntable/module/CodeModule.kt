package com.dataturntable.module

import com.dataturntable.auth.User
import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Defines the enum class `ModuleType`, the contract implemented by this source unit.
 */
enum class ModuleType {
    /** Receives raw data from a connector and parses/transforms it. Platform fetches — no network I/O in module. */
    IMPORT,
    /** Platform-managed database source node. Compatible with IMPORT graph nodes. */
    DB_LOAD,
    /** Transforms, filters, or enriches data. Single upstream input. */
    PROCESSING,
    /** Fan-in node: merges two or more upstream outputs into one. Different class/signature from PROCESSING. */
    MERGE,
    /** Evaluates conditions and dispatches alerts via a predefined alert channel. Terminal node. */
    ALERT,
    /** Shapes the outbound payload. Platform delivers — no network I/O in module. Terminal node. */
    EXPORT,
    /** Platform-managed database sink node. Compatible with EXPORT graph nodes. */
    DB_SAVE
}

@Entity
@Table(name = "code_modules")
/**
 * Defines the class `CodeModule`, the contract implemented by this source unit.
 */
open class CodeModule : PanacheBase() {
    
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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_version_id")
    var defaultVersion: ModuleVersion? = null
    
    companion object : PanacheCompanionBase<CodeModule, UUID> {
        override fun findById(id: UUID): CodeModule? =
            find("id = ?1", id).firstResult()

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantId(tenantId: UUID): List<CodeModule> =
            list("tenant.id = ?1", tenantId)

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantAndType(tenantId: UUID, type: ModuleType): List<CodeModule> =
            list("tenant.id = ?1 AND moduleType = ?2", tenantId, type)

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantAndName(tenantId: UUID, name: String, type: ModuleType): CodeModule? =
            find("tenant.id = ?1 AND name = ?2 AND moduleType = ?3", tenantId, name, type).firstResult()
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getLatestVersion(): ModuleVersion? = 
        versions.firstOrNull()
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getActiveVersion(): ModuleVersion? = 
        versions.firstOrNull { it.isActive }

    /**
     * Implements `resolveDefaultVersion` for this source unit.
     */
    fun resolveDefaultVersion(): ModuleVersion? =
        defaultVersion ?: getActiveVersion() ?: getLatestVersion()
}
