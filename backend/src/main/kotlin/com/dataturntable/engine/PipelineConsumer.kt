package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.time.Instant

data class PipelineMessage(
    val runId: String,
    val pipelineId: String,
    val triggerType: String,
    val data: Any? = null
)

@ApplicationScoped
class PipelineConsumer {
    
    private val LOG = Logger.getLogger(PipelineConsumer::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var dockerService: DockerService
    
    @Incoming("pipeline-import")
    fun consumeImport(message: Message<String>): Uni<Void> {
        return Uni.createFrom().item(message).map { msg ->
            try {
                val payload = objectMapper.readTree(msg.payload)
                val pipelineId = payload.get("pipelineId")?.asText()
                val runId = payload.get("runId")?.asText()
                val data = payload.get("data")
                
                if (pipelineId != null && runId != null) {
                    processImport(pipelineId, runId, data)
                }
                
                msg.ack()
            } catch (e: Exception) {
                LOG.error("Error processing import message", e)
                msg.nack(e)
            }
            null
        }.onItem().ignore().andContinueWithNull()
    }
    
    @Incoming("pipeline-processing")
    fun consumeProcessing(message: Message<String>): Uni<Void> {
        return Uni.createFrom().item(message).map { msg ->
            try {
                val payload = objectMapper.readTree(msg.payload)
                val pipelineId = payload.get("pipelineId")?.asText()
                val runId = payload.get("runId")?.asText()
                val data = payload.get("data")
                
                if (pipelineId != null && runId != null) {
                    processProcessing(pipelineId, runId, data)
                }
                
                msg.ack()
            } catch (e: Exception) {
                LOG.error("Error processing message", e)
                msg.nack(e)
            }
            null
        }.onItem().ignore().andContinueWithNull()
    }
    
    @Incoming("pipeline-export")
    fun consumeExport(message: Message<String>): Uni<Void> {
        return Uni.createFrom().item(message).map { msg ->
            try {
                val payload = objectMapper.readTree(msg.payload)
                val pipelineId = payload.get("pipelineId")?.asText()
                val runId = payload.get("runId")?.asText()
                val data = payload.get("data")
                
                if (pipelineId != null && runId != null) {
                    processExport(pipelineId, runId, data)
                }
                
                msg.ack()
            } catch (e: Exception) {
                LOG.error("Error processing export message", e)
                msg.nack(e)
            }
            null
        }.onItem().ignore().andContinueWithNull()
    }
    
    private fun processImport(pipelineId: String, runId: String, data: JsonNode?) {
        LOG.infof("Processing import for pipeline: %s, run: %s", pipelineId, runId)
        
        val pipeline = Pipeline.findById(java.util.UUID.fromString(pipelineId)) ?: run {
            LOG.error("Pipeline not found: $pipelineId")
            return
        }
        
        // Update run status
        val run = PipelineRun.findById(java.util.UUID.fromString(runId))
        run?.markRunning()
        
        // Get import module version
        val importVersion = pipeline.importModuleVersionId?.let { ModuleVersion.findById(it) }
        
        if (importVersion != null) {
            try {
                // Execute import module in Docker
                val result = dockerService.executeModule(
                    moduleVersion = importVersion,
                    inputData = data,
                    pipelineId = pipelineId,
                    runId = runId,
                    moduleType = ModuleType.IMPORT
                )
                
                // Send to processing topic
                // In a real implementation, this would send to Kafka
                LOG.infof("Import module executed successfully, result: %s", result)
                
                run?.markSuccess()
            } catch (e: Exception) {
                LOG.error("Error executing import module", e)
                run?.markFailed(e.message)
            }
        } else {
            // No import module, pass data directly to processing
            LOG.info("No import module configured, passing data to processing")
            run?.markSuccess()
        }
    }
    
    private fun processProcessing(pipelineId: String, runId: String, data: JsonNode?) {
        LOG.infof("Processing processing step for pipeline: %s, run: %s", pipelineId, runId)
        
        val pipeline = Pipeline.findById(java.util.UUID.fromString(pipelineId)) ?: run {
            LOG.error("Pipeline not found: $pipelineId")
            return
        }
        
        // Process through each processing module sequentially
        val processingVersionIds = pipeline.processingModuleVersionIds
        
        for (versionId in processingVersionIds) {
            val version = ModuleVersion.findById(versionId) ?: continue
            
            try {
                val result = dockerService.executeModule(
                    moduleVersion = version,
                    inputData = data,
                    pipelineId = pipelineId,
                    runId = runId,
                    moduleType = ModuleType.PROCESSING
                )
                
                LOG.infof("Processing module executed: %s", version.module?.name)
                
                // Update data for next module
                // In real implementation, parse result and continue
            } catch (e: Exception) {
                LOG.error("Error executing processing module", e)
                val run = PipelineRun.findById(java.util.UUID.fromString(runId))
                run?.markFailed(e.message)
                return
            }
        }
        
        // After all processing, send to export
        LOG.info("All processing modules executed, sending to export")
    }
    
    private fun processExport(pipelineId: String, runId: String, data: JsonNode?) {
        LOG.infof("Processing export for pipeline: %s, run: %s", pipelineId, runId)
        
        val pipeline = Pipeline.findById(java.util.UUID.fromString(pipelineId)) ?: run {
            LOG.error("Pipeline not found: $pipelineId")
            return
        }
        
        val run = PipelineRun.findById(java.util.UUID.fromString(runId))
        
        // Get export module versions
        val exportVersionIds = pipeline.exportModuleVersionIds
        
        for (versionId in exportVersionIds) {
            val version = ModuleVersion.findById(versionId) ?: continue
            
            try {
                dockerService.executeModule(
                    moduleVersion = version,
                    inputData = data,
                    pipelineId = pipelineId,
                    runId = runId,
                    moduleType = ModuleType.EXPORT
                )
                
                LOG.infof("Export module executed: %s", version.module?.name)
            } catch (e: Exception) {
                LOG.error("Error executing export module", e)
                run?.markFailed(e.message)
                return
            }
        }
        
        run?.markSuccess()
        LOG.infof("Pipeline completed successfully: %s", pipelineId)
    }
}
