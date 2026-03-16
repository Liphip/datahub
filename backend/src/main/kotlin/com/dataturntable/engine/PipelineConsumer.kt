package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.reactive.messaging.annotations.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Outgoing
import org.jboss.logging.Logger
import java.util.UUID

data class PipelineTriggerMessage(
    val runId: String,
    val pipelineId: String,
    val triggerType: String,
    val data: Any? = null
)

data class PipelineEvent(
    val runId: String,
    val pipelineId: String,
    val stage: String,
    val success: Boolean,
    val result: Any? = null,
    val error: String? = null
)

@ApplicationScoped
class PipelineConsumer {
    
    private val LOG = Logger.getLogger(PipelineConsumer::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var dockerService: DockerService
    
    @Incoming("pipeline-input")
    @Outgoing("pipeline-processing")
    @Blocking
    @Transactional
    fun consume(message: String): PipelineEvent {
        LOG.infof("Received pipeline message: %s", message)
        
        val pipelineMsg: PipelineTriggerMessage
        try {
            pipelineMsg = objectMapper.readValue(message, PipelineTriggerMessage::class.java)
        } catch (e: Exception) {
            LOG.errorf("Failed to parse pipeline message: %s", e.message)
            return PipelineEvent(
                runId = "unknown",
                pipelineId = "unknown",
                stage = "parse",
                success = false,
                error = e.message
            )
        }
        
        val pipeline = Pipeline.findById(UUID.fromString(pipelineMsg.pipelineId))
        if (pipeline == null) {
            LOG.errorf("Pipeline not found: %s", pipelineMsg.pipelineId)
            return PipelineEvent(
                runId = pipelineMsg.runId,
                pipelineId = pipelineMsg.pipelineId,
                stage = "init",
                success = false,
                error = "Pipeline not found"
            )
        }
        
        val run = PipelineRun.findById(UUID.fromString(pipelineMsg.runId))
        if (run != null) {
            run.status = PipelineRunStatus.RUNNING
            run.startedAt = java.time.Instant.now()
            run.persist()
        }
        
        try {
            val result = executePipeline(pipeline, pipelineMsg)
            
            if (run != null) {
                run.status = PipelineRunStatus.SUCCESS
                run.completedAt = java.time.Instant.now()
                run.persist()
            }
            
            return PipelineEvent(
                runId = pipelineMsg.runId,
                pipelineId = pipelineMsg.pipelineId,
                stage = "complete",
                success = true,
                result = result
            )
        } catch (e: Exception) {
            LOG.errorf("Pipeline execution failed: %s", e.message)
            
            if (run != null) {
                run.status = PipelineRunStatus.FAILED
                run.completedAt = java.time.Instant.now()
                run.errorMessage = e.message
                run.persist()
            }
            
            return PipelineEvent(
                runId = pipelineMsg.runId,
                pipelineId = pipelineMsg.pipelineId,
                stage = "error",
                success = false,
                error = e.message
            )
        }
    }
    
    private fun executePipeline(pipeline: Pipeline, message: PipelineTriggerMessage): Map<String, Any?> {
        val results = mutableMapOf<String, Any?>()
        
        pipeline.importModuleVersionId?.let { importId ->
            val importVersion = ModuleVersion.findById(importId)
            if (importVersion != null) {
                LOG.infof("Executing import module: %s", importVersion.module?.name)
                val importResult = dockerService.executeModule(
                    importVersion,
                    null,
                    pipeline.id.toString(),
                    message.runId,
                    ModuleType.IMPORT
                )
                results["import"] = importResult
            }
        }
        
        pipeline.processingModuleVersionIds.forEachIndexed { index, processingId ->
            val processingVersion = ModuleVersion.findById(processingId)
            if (processingVersion != null) {
                LOG.infof("Executing processing module %d: %s", index + 1, processingVersion.module?.name)
                val processingResult = dockerService.executeModule(
                    processingVersion,
                    null,
                    pipeline.id.toString(),
                    message.runId,
                    ModuleType.PROCESSING
                )
                results["processing_${index + 1}"] = processingResult
            }
        }
        
        pipeline.exportModuleVersionIds.forEachIndexed { index, exportId ->
            val exportVersion = ModuleVersion.findById(exportId)
            if (exportVersion != null) {
                LOG.infof("Executing export module %d: %s", index + 1, exportVersion.module?.name)
                val exportResult = dockerService.executeModule(
                    exportVersion,
                    null,
                    pipeline.id.toString(),
                    message.runId,
                    ModuleType.EXPORT
                )
                results["export_${index + 1}"] = exportResult
            }
        }
        
        return results
    }
}
