package com.dataturntable.pipeline

import com.dataturntable.auth.User
import com.dataturntable.tenant.Tenant
import com.dataturntable.module.ModuleVersion
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pipelines")
open class Pipeline : PanacheBase() {
    
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
    
    @Column(name = "import_module_version_id")
    var importModuleVersionId: UUID? = null
    
    @Column(name = "processing_module_version_ids", columnDefinition = "UUID ARRAY")
    var processingModuleVersionIds: Array<UUID> = emptyArray()
    
    @Column(name = "export_module_version_ids", columnDefinition = "UUID ARRAY")
    var exportModuleVersionIds: Array<UUID> = emptyArray()
    
    @Column(name = "kafka_consumer_group", unique = true)
    var kafkaConsumerGroup: String? = null
    
    @Column(name = "is_active")
    var isActive: Boolean = false
    
    @Column(columnDefinition = "text")
    var config: String? = null

    @Column(name = "graph_definition", columnDefinition = "text")
    var graphDefinition: String? = null
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null
    
    @OneToMany(mappedBy = "pipeline", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @OrderBy("startedAt DESC")
    var runs: MutableList<PipelineRun> = mutableListOf()
    
    companion object : PanacheCompanionBase<Pipeline, UUID> {
        override fun findById(id: UUID): Pipeline? =
            getEntityManager()
                .createQuery("SELECT p FROM Pipeline p WHERE p.id = :id", Pipeline::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
        
        fun findByTenantId(tenantId: UUID): List<Pipeline> =
            getEntityManager()
                .createQuery("SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId", Pipeline::class.java)
                .setParameter("tenantId", tenantId)
                .resultList
        
        fun findActiveByTenant(tenantId: UUID): List<Pipeline> =
            getEntityManager()
                .createQuery(
                    "SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId AND p.isActive = true",
                    Pipeline::class.java
                )
                .setParameter("tenantId", tenantId)
                .resultList
        
        fun findByConsumerGroup(group: String): Pipeline? =
            getEntityManager()
                .createQuery("SELECT p FROM Pipeline p WHERE p.kafkaConsumerGroup = :group", Pipeline::class.java)
                .setParameter("group", group)
                .resultList
                .firstOrNull()
    }
    
    fun getProcessingVersions(): List<ModuleVersion> {
        return processingModuleVersionIds.mapNotNull { id ->
            ModuleVersion.findById(id)
        }
    }
    
    fun getExportVersions(): List<ModuleVersion> {
        return exportModuleVersionIds.mapNotNull { id ->
            ModuleVersion.findById(id)
        }
    }
}
