package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.*

@ApplicationScoped
class PipelineService {
    
    @Inject
    lateinit var kafkaService: KafkaService
    
    private val objectMapper = ObjectMapper()
    
    fun getPipelines(tenantId: UUID): List<PipelineDto> {
        return Pipeline.findByTenantId(tenantId).map { toDto(it) }
    }
    
    fun getPipeline(id: UUID): PipelineDto? {
        val pipeline = Pipeline.findById(id) ?: return null
        return toDto(pipeline)
    }
    
    @Transactional
    fun createPipeline(tenantId: UUID, request: CreatePipelineRequest, user: User): PipelineDto {
        val tenant = Tenant.findById(tenantId)
            ?: throw IllegalArgumentException("Tenant not found")
        
        val consumerGroup = "dt-pipeline-${UUID.randomUUID()}"
        
        val pipeline = Pipeline().apply {
            this.tenant = tenant
            this.name = request.name
            this.description = request.description
            this.importModuleVersionId = if (request.importModuleVersionId != null) 
                UUID.fromString(request.importModuleVersionId) else null
            this.processingModuleVersionIds = request.processingModuleVersionIds.map { UUID.fromString(it) }.toTypedArray()
            this.exportModuleVersionIds = request.exportModuleVersionIds.map { UUID.fromString(it) }.toTypedArray()
            this.kafkaConsumerGroup = consumerGroup
            this.config = objectMapper.writeValueAsString(request.config)
            this.createdBy = user
        }
        
        pipeline.persist()
        
        // Create Kafka topics for this pipeline
        kafkaService.createPipelineTopics(pipeline.id!!.toString(), consumerGroup)
        
        return toDto(pipeline)
    }
    
    @Transactional
    fun updatePipeline(id: UUID, request: UpdatePipelineRequest): PipelineDto? {
        val pipeline = Pipeline.findById(id) ?: return null
        
        request.name?.let { pipeline.name = it }
        request.description?.let { pipeline.description = it }
        request.importModuleVersionId?.let { 
            pipeline.importModuleVersionId = if (it.isNotEmpty()) UUID.fromString(it) else null
        }
        request.processingModuleVersionIds?.let { 
            pipeline.processingModuleVersionIds = it.map { id -> UUID.fromString(id) }.toTypedArray()
        }
        request.exportModuleVersionIds?.let { 
            pipeline.exportModuleVersionIds = it.map { id -> UUID.fromString(id) }.toTypedArray()
        }
        request.isActive?.let { 
            pipeline.isActive = it
            if (it) {
                kafkaService.startPipelineConsumers(pipeline)
            } else {
                kafkaService.stopPipelineConsumers(pipeline)
            }
        }
        request.config?.let { pipeline.config = objectMapper.writeValueAsString(it) }
        
        pipeline.updatedAt = java.time.Instant.now()
        pipeline.persist()
        
        return toDto(pipeline)
    }
    
    @Transactional
    fun deletePipeline(id: UUID): Boolean {
        val pipeline = Pipeline.findById(id) ?: return false
        
        // Delete Kafka topics
        kafkaService.deletePipelineTopics(id.toString())
        
        pipeline.delete()
        return true
    }
    
    @Transactional
    fun triggerPipeline(id: UUID, triggerType: TriggerType): PipelineRunDto? {
        val pipeline = Pipeline.findById(id) ?: return null
        
        val run = PipelineRun().apply {
            this.pipeline = pipeline
            this.triggerType = triggerType
            this.status = PipelineRunStatus.PENDING
        }
        run.persist()
        
        // Send trigger message to Kafka
        kafkaService.triggerPipeline(pipeline.id!!.toString(), run.id!!.toString(), triggerType)
        
        return toRunDto(run)
    }
    
    fun getRuns(pipelineId: UUID): List<PipelineRunDto> {
        return PipelineRun.findByPipelineId(pipelineId).map { toRunDto(it) }
    }
    
    fun toDto(pipeline: Pipeline): PipelineDto {
        val runs = pipeline.runs.take(10).map { toRunDto(it) }
        
        return PipelineDto(
            id = pipeline.id.toString(),
            tenantId = pipeline.tenant?.id.toString(),
            name = pipeline.name ?: "",
            description = pipeline.description,
            importModuleVersionId = pipeline.importModuleVersionId?.toString(),
            processingModuleVersionIds = pipeline.processingModuleVersionIds.map { it.toString() },
            exportModuleVersionIds = pipeline.exportModuleVersionIds.map { it.toString() },
            kafkaConsumerGroup = pipeline.kafkaConsumerGroup,
            isActive = pipeline.isActive,
            config = try {
                objectMapper.readValue(pipeline.config, Map::class.java) as? Map<String, Any> ?: emptyMap()
            } catch (e: Exception) { emptyMap() },
            createdAt = pipeline.createdAt,
            runs = runs
        )
    }
    
    fun toRunDto(run: PipelineRun): PipelineRunDto {
        return PipelineRunDto(
            id = run.id.toString(),
            pipelineId = run.pipeline?.id.toString(),
            status = run.status.name,
            triggerType = run.triggerType,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
            errorMessage = run.errorMessage,
            metrics = try {
                objectMapper.readValue(run.metrics, Map::class.java) as? Map<String, Any> ?: emptyMap()
            } catch (e: Exception) { emptyMap() }
        )
    }
}
