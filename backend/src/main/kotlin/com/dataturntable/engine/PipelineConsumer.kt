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
    @Blocking
    @Transactional
    fun consume(message: String): PipelineEvent {
        LOG.infof("Received pipeline message: %s", message.take(200))
        
        val pipelineMsg: PipelineTriggerMessage?
        var dataPayload: Map<String, Any>? = null
        var pipelineId: String? = null
        
        try {
            pipelineMsg = try {
                objectMapper.readValue(message, PipelineTriggerMessage::class.java)
            } catch (e: Exception) {
                null
            }
            
            if (pipelineMsg != null) {
                pipelineId = pipelineMsg.pipelineId
            } else {
                try {
                    @Suppress("UNCHECKED_CAST")
                    dataPayload = objectMapper.readValue(message, Map::class.java) as? Map<String, Any>
                    pipelineId = (dataPayload?.get("_pipelineId") as? String)
                        ?: (dataPayload?.get("pipelineId") as? String)
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
            }
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
        
        if (pipelineId == null) {
            LOG.error("No pipeline ID found in message")
            return PipelineEvent(
                runId = "unknown",
                pipelineId = "unknown",
                stage = "init",
                success = false,
                error = "No pipeline ID in message"
            )
        }

        val pipelineUuid = try {
            UUID.fromString(pipelineId)
        } catch (_: Exception) {
            return PipelineEvent(
                runId = "unknown",
                pipelineId = pipelineId,
                stage = "init",
                success = false,
                error = "Invalid pipeline ID"
            )
        }
        
        val pipeline = Pipeline.findById(pipelineUuid)
        if (pipeline == null) {
            LOG.errorf("Pipeline not found: %s", pipelineId)
            return PipelineEvent(
                runId = pipelineMsg?.runId ?: "unknown",
                pipelineId = pipelineId,
                stage = "init",
                success = false,
                error = "Pipeline not found"
            )
        }
        
        val runId = pipelineMsg?.runId
            ?: (dataPayload?.get("_runId") as? String)
            ?: (dataPayload?.get("runId") as? String)
            ?: UUID.randomUUID().toString()

        val runUuid = try {
            UUID.fromString(runId)
        } catch (_: Exception) {
            null
        }
        val run = runUuid?.let { PipelineRun.findById(it) }
        if (run != null) {
            run.status = PipelineRunStatus.RUNNING
            run.startedAt = java.time.Instant.now()
            run.persist()
        }
        
        try {
            val result = executePipeline(pipeline, runId, dataPayload)
            
            if (run != null) {
                run.status = PipelineRunStatus.SUCCESS
                run.completedAt = java.time.Instant.now()
                run.persist()
            }
            
            return PipelineEvent(
                runId = runId,
                pipelineId = pipelineId,
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
                runId = runId,
                pipelineId = pipelineId,
                stage = "error",
                success = false,
                error = e.message
            )
        }
    }
    
    private fun executePipeline(pipeline: Pipeline, runId: String, inputData: Map<String, Any>?): Map<String, Any?> {
        val results = mutableMapOf<String, Any?>()
        
        var data = inputData
        
        pipeline.importModuleVersionId?.let { importId ->
            val importVersion = ModuleVersion.findById(importId)
            if (importVersion != null) {
                LOG.infof("Executing import module: %s", importVersion.module?.name)
                val importResult = dockerService.executeModule(
                    importVersion,
                    data,
                    pipeline.id.toString(),
                    runId,
                    ModuleType.IMPORT
                )
                results["import"] = importResult
                if (importResult is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    data = importResult as? Map<String, Any>
                }
            }
        }
        
        pipeline.processingModuleVersionIds.forEachIndexed { index, processingId ->
            val processingVersion = ModuleVersion.findById(processingId)
            if (processingVersion != null && data != null) {
                LOG.infof("Executing processing module %d: %s", index + 1, processingVersion.module?.name)
                val processingResult = dockerService.executeModule(
                    processingVersion,
                    data,
                    pipeline.id.toString(),
                    runId,
                    ModuleType.PROCESSING
                )
                results["processing_${index + 1}"] = processingResult
                if (processingResult is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    data = processingResult as? Map<String, Any>
                }
            }
        }
        
        pipeline.exportModuleVersionIds.forEachIndexed { index, exportId ->
            val exportVersion = ModuleVersion.findById(exportId)
            if (exportVersion != null && data != null) {
                LOG.infof("Executing export module %d: %s", index + 1, exportVersion.module?.name)
                val exportResult = dockerService.executeModule(
                    exportVersion,
                    data,
                    pipeline.id.toString(),
                    runId,
                    ModuleType.EXPORT
                )
                results["export_${index + 1}"] = exportResult
            }
        }
        
        return results
    }
}
