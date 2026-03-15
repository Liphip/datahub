package com.dataturntable.pipeline

import com.dataturntable.auth.User
import com.dataturntable.module.ModuleVersion
import com.dataturntable.tenant.Tenant
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pipelines")
class Pipeline : PanacheEntityBase() {
    
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
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_module_version_id")
    var importModuleVersion: ModuleVersion? = null
    
    @Column(name = "processing_module_version_ids", columnDefinition = "uuid[]")
    var processingModuleVersionIds: Array<UUID> = emptyArray()
    
    @Column(name = "export_module_version_ids", columnDefinition = "uuid[]")
    var exportModuleVersionIds: Array<UUID> = emptyArray()
    
    @Column(name = "kafka_consumer_group", unique = true)
    var kafkaConsumerGroup: String? = null
    
    @Column(name = "is_active")
    var isActive: Boolean = false
    
    @Column(columnDefinition = "jsonb")
    var config: String = "{}"
    
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
    
    fun getProcessingVersions(): List<ModuleVersion> {
        return emptyList()
    }
    
    fun getExportVersions(): List<ModuleVersion> {
        return emptyList()
    }
}
