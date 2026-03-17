package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.alert.AlertDispatcher
import com.dataturntable.connectors.BrokerCredential
import com.dataturntable.connectors.Connector
import com.dataturntable.security.EncryptionUtils
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.reactive.messaging.annotations.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.reactive.messaging.Incoming
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

    private fun trimLog(value: String, maxLength: Int = 8000): String {
        if (value.length <= maxLength) return value
        return value.take(maxLength) + "\n...<truncated>"
    }
    
    @Inject
    lateinit var dockerService: DockerService

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @PersistenceContext
    lateinit var em: EntityManager

    private data class ResolvedConnector(
        val config: Map<String, Any?>,
        val summary: Map<String, Any?>
    )

    private fun parseJsonMap(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(raw, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveConnectorConfigsByModuleVersion(pipeline: Pipeline): Map<UUID, ResolvedConnector> {
        val tenantId = pipeline.tenant?.id ?: return emptyMap()
        val graph = parseJsonMap(pipeline.graphDefinition)
        val nodes = graph["nodes"] as? List<*> ?: return emptyMap()
        val resolved = mutableMapOf<UUID, ResolvedConnector>()

        nodes.forEach { node ->
            val nodeMap = node as? Map<*, *> ?: return@forEach
            val nodeType = nodeMap["type"]?.toString()?.uppercase() ?: return@forEach
            if (nodeType != "IMPORT" && nodeType != "EXPORT") return@forEach

            val moduleVersionId = parseUuid(nodeMap["moduleVersionId"]?.toString()) ?: return@forEach
            val connectorId = parseUuid(nodeMap["connectorId"]?.toString()) ?: return@forEach
            val connector = em.find(Connector::class.java, connectorId) ?: return@forEach
            if (connector.tenant?.id != tenantId) return@forEach

            val rawConfig = try {
                EncryptionUtils.decrypt(connector.config)
            } catch (_: Exception) {
                "{}"
            }
            val config = parseJsonMap(rawConfig).toMutableMap()

            if (connector.connectorType == Connector.ConnectorType.MQTT) {
                val brokerCredentialId = parseUuid(config["brokerCredentialId"]?.toString())
                if (brokerCredentialId != null) {
                    val credential = em.find(BrokerCredential::class.java, brokerCredentialId)
                    if (credential != null && credential.tenant?.id == tenantId && credential.isActive) {
                        config["username"] = credential.username
                        val password = credential.passwordEncrypted?.let {
                            try {
                                EncryptionUtils.decrypt(it)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (!password.isNullOrBlank()) {
                            config["password"] = password
                        }
                        config["brokerCredentialName"] = credential.name
                    }
                }
            }

            val summary = mapOf(
                "id" to connector.id?.toString(),
                "name" to connector.name,
                "connectorType" to connector.connectorType?.name,
                "direction" to connector.direction?.name,
                "broker" to config["broker"],
                "topic" to config["topic"],
                "username" to config["username"],
                "brokerCredentialId" to config["brokerCredentialId"],
                "brokerCredentialName" to config["brokerCredentialName"]
            ).filterValues { it != null }

            resolved[moduleVersionId] = ResolvedConnector(config = config, summary = summary)
        }

        return resolved
    }

    private fun resolveAlertModuleVersionsFromGraph(pipeline: Pipeline): List<ModuleVersion> {
        val graph = parseJsonMap(pipeline.graphDefinition)
        val nodes = graph["nodes"] as? List<*> ?: return emptyList()
        return nodes.mapNotNull { node ->
            val nodeMap = node as? Map<*, *> ?: return@mapNotNull null
            val nodeType = nodeMap["type"]?.toString()?.uppercase() ?: return@mapNotNull null
            if (nodeType != "ALERT") return@mapNotNull null
            val moduleVersionId = parseUuid(nodeMap["moduleVersionId"]?.toString()) ?: return@mapNotNull null
            ModuleVersion.findById(moduleVersionId)
        }
    }
    
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
                if (pipelineMsg.data is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    dataPayload = pipelineMsg.data as? Map<String, Any>
                }
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
            if (run.status == PipelineRunStatus.CANCELLED) {
                return PipelineEvent(
                    runId = runId,
                    pipelineId = pipelineId,
                    stage = "cancelled",
                    success = false,
                    error = "Run cancelled"
                )
            }
            run.status = PipelineRunStatus.RUNNING
            run.startedAt = java.time.Instant.now()
            run.persist()
        }
        val executionStartedAt = System.currentTimeMillis()

        try {
            val result = executePipeline(pipeline, runId, dataPayload)
            val executionMs = System.currentTimeMillis() - executionStartedAt
            
            if (run != null) {
                val currentStatus = run.id?.let { em.find(PipelineRun::class.java, it)?.status } ?: run.status
                if (currentStatus == PipelineRunStatus.CANCELLED) {
                    return PipelineEvent(
                        runId = runId,
                        pipelineId = pipelineId,
                        stage = "cancelled",
                        success = false,
                        error = "Run cancelled"
                    )
                }
                run.status = PipelineRunStatus.SUCCESS
                run.completedAt = java.time.Instant.now()
                run.metrics = objectMapper.writeValueAsString(
                    mapOf(
                        "executionMs" to executionMs,
                        "result" to result["result"],
                        "moduleLogs" to result["moduleLogs"],
                        "executionLog" to result["executionLog"]
                    )
                )
                run.persist()
            }
            
            return PipelineEvent(
                runId = runId,
                pipelineId = pipelineId,
                stage = "complete",
                success = true,
                result = result["result"]
            )
        } catch (e: Exception) {
            LOG.errorf("Pipeline execution failed: %s", e.message)
            val executionMs = System.currentTimeMillis() - executionStartedAt
            
            if (run != null) {
                val currentStatus = run.id?.let { em.find(PipelineRun::class.java, it)?.status } ?: run.status
                if (currentStatus == PipelineRunStatus.CANCELLED) {
                    return PipelineEvent(
                        runId = runId,
                        pipelineId = pipelineId,
                        stage = "cancelled",
                        success = false,
                        error = "Run cancelled"
                    )
                }
                run.status = PipelineRunStatus.FAILED
                run.completedAt = java.time.Instant.now()
                run.errorMessage = e.message
                val moduleException = e as? ModuleExecutionException
                run.metrics = objectMapper.writeValueAsString(
                    mapOf(
                        "executionMs" to executionMs,
                        "error" to (e.message ?: "Pipeline execution failed"),
                        "stdout" to (moduleException?.stdout?.let { trimLog(it) }),
                        "stderr" to (moduleException?.stderr?.let { trimLog(it) }),
                        "exitCode" to moduleException?.exitCode
                    )
                )
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
        val moduleLogs = mutableListOf<Map<String, Any?>>()
        val executionLog = mutableListOf<String>()
        val connectorConfigsByVersion = resolveConnectorConfigsByModuleVersion(pipeline)
        
        var data = inputData
        
        pipeline.importModuleVersionId?.let { importId ->
            val importVersion = ModuleVersion.findById(importId)
            if (importVersion != null) {
                val connectorContext = importVersion.id?.let { connectorConfigsByVersion[it] }
                LOG.infof("Executing import module: %s", importVersion.module?.name)
                executionLog.add("START IMPORT ${importVersion.module?.name ?: importVersion.id}")
                try {
                    val importOutput = dockerService.executeModule(
                        importVersion,
                        data,
                        pipeline.id.toString(),
                        runId,
                        ModuleType.IMPORT,
                        connectorContext?.config
                    )
                    val importResult = importOutput.result
                    results["import"] = importResult
                    moduleLogs.add(mapOf(
                        "stage" to "IMPORT",
                        "moduleVersionId" to importVersion.id,
                        "moduleName" to importVersion.module?.name,
                        "success" to true,
                        "stdout" to trimLog(importOutput.stdout),
                        "stderr" to trimLog(importOutput.stderr),
                        "exitCode" to importOutput.exitCode,
                        "durationMs" to importOutput.durationMs,
                        "connector" to connectorContext?.summary
                    ))
                    executionLog.add("DONE IMPORT ${importVersion.module?.name ?: importVersion.id} (${importOutput.durationMs}ms)")
                    if (importResult is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        data = importResult as? Map<String, Any>
                    }
                } catch (e: ModuleExecutionException) {
                    moduleLogs.add(mapOf(
                        "stage" to "IMPORT",
                        "moduleVersionId" to importVersion.id,
                        "moduleName" to importVersion.module?.name,
                        "success" to false,
                        "stdout" to trimLog(e.stdout),
                        "stderr" to trimLog(e.stderr),
                        "exitCode" to e.exitCode,
                        "error" to e.message,
                        "connector" to connectorContext?.summary
                    ))
                    executionLog.add("FAIL IMPORT ${importVersion.module?.name ?: importVersion.id}: ${e.message}")
                    throw e
                }
            }
        }
        
        pipeline.processingModuleVersionIds.forEachIndexed { index, processingId ->
            val processingVersion = ModuleVersion.findById(processingId)
            if (processingVersion != null && data != null) {
                LOG.infof("Executing processing module %d: %s", index + 1, processingVersion.module?.name)
                executionLog.add("START PROCESSING #${index + 1} ${processingVersion.module?.name ?: processingVersion.id}")
                try {
                    val processingOutput = dockerService.executeModule(
                        processingVersion,
                        data,
                        pipeline.id.toString(),
                        runId,
                        ModuleType.PROCESSING
                    )
                    val processingResult = processingOutput.result
                    results["processing_${index + 1}"] = processingResult
                    moduleLogs.add(mapOf(
                        "stage" to "PROCESSING",
                        "order" to (index + 1),
                        "moduleVersionId" to processingVersion.id,
                        "moduleName" to processingVersion.module?.name,
                        "success" to true,
                        "stdout" to trimLog(processingOutput.stdout),
                        "stderr" to trimLog(processingOutput.stderr),
                        "exitCode" to processingOutput.exitCode,
                        "durationMs" to processingOutput.durationMs
                    ))
                    executionLog.add("DONE PROCESSING #${index + 1} ${processingVersion.module?.name ?: processingVersion.id} (${processingOutput.durationMs}ms)")
                    if (processingResult is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        data = processingResult as? Map<String, Any>
                    }
                } catch (e: ModuleExecutionException) {
                    moduleLogs.add(mapOf(
                        "stage" to "PROCESSING",
                        "order" to (index + 1),
                        "moduleVersionId" to processingVersion.id,
                        "moduleName" to processingVersion.module?.name,
                        "success" to false,
                        "stdout" to trimLog(e.stdout),
                        "stderr" to trimLog(e.stderr),
                        "exitCode" to e.exitCode,
                        "error" to e.message
                    ))
                    executionLog.add("FAIL PROCESSING #${index + 1} ${processingVersion.module?.name ?: processingVersion.id}: ${e.message}")
                    throw e
                }
            }
        }
        
        pipeline.exportModuleVersionIds.forEachIndexed { index, exportId ->
            val exportVersion = ModuleVersion.findById(exportId)
            if (exportVersion != null && data != null) {
                val connectorContext = exportVersion.id?.let { connectorConfigsByVersion[it] }
                LOG.infof("Executing export module %d: %s", index + 1, exportVersion.module?.name)
                executionLog.add("START EXPORT #${index + 1} ${exportVersion.module?.name ?: exportVersion.id}")
                try {
                    val exportOutput = dockerService.executeModule(
                        exportVersion,
                        data,
                        pipeline.id.toString(),
                        runId,
                        ModuleType.EXPORT,
                        connectorContext?.config
                    )
                    val exportResult = exportOutput.result
                    results["export_${index + 1}"] = exportResult
                    moduleLogs.add(mapOf(
                        "stage" to "EXPORT",
                        "order" to (index + 1),
                        "moduleVersionId" to exportVersion.id,
                        "moduleName" to exportVersion.module?.name,
                        "success" to true,
                        "stdout" to trimLog(exportOutput.stdout),
                        "stderr" to trimLog(exportOutput.stderr),
                        "exitCode" to exportOutput.exitCode,
                        "durationMs" to exportOutput.durationMs,
                        "connector" to connectorContext?.summary
                    ))
                    executionLog.add("DONE EXPORT #${index + 1} ${exportVersion.module?.name ?: exportVersion.id} (${exportOutput.durationMs}ms)")
                } catch (e: ModuleExecutionException) {
                    moduleLogs.add(mapOf(
                        "stage" to "EXPORT",
                        "order" to (index + 1),
                        "moduleVersionId" to exportVersion.id,
                        "moduleName" to exportVersion.module?.name,
                        "success" to false,
                        "stdout" to trimLog(e.stdout),
                        "stderr" to trimLog(e.stderr),
                        "exitCode" to e.exitCode,
                        "error" to e.message,
                        "connector" to connectorContext?.summary
                    ))
                    executionLog.add("FAIL EXPORT #${index + 1} ${exportVersion.module?.name ?: exportVersion.id}: ${e.message}")
                    throw e
                }
            }
        }

        val graphAlertVersions = resolveAlertModuleVersionsFromGraph(pipeline)
        graphAlertVersions.forEachIndexed { index, alertVersion ->
            if (data == null) return@forEachIndexed
            LOG.infof("Executing alert module %d: %s", index + 1, alertVersion.module?.name)
            executionLog.add("START ALERT #${index + 1} ${alertVersion.module?.name ?: alertVersion.id}")
            try {
                val alertOutput = dockerService.executeModule(
                    alertVersion,
                    data,
                    pipeline.id.toString(),
                    runId,
                    ModuleType.ALERT
                )
                results["alert_${index + 1}"] = alertOutput.result
                moduleLogs.add(mapOf(
                    "stage" to "ALERT",
                    "order" to (index + 1),
                    "moduleVersionId" to alertVersion.id,
                    "moduleName" to alertVersion.module?.name,
                    "success" to true,
                    "stdout" to trimLog(alertOutput.stdout),
                    "stderr" to trimLog(alertOutput.stderr),
                    "exitCode" to alertOutput.exitCode,
                    "durationMs" to alertOutput.durationMs
                ))
                executionLog.add("DONE ALERT #${index + 1} ${alertVersion.module?.name ?: alertVersion.id} (${alertOutput.durationMs}ms)")
            } catch (e: ModuleExecutionException) {
                moduleLogs.add(mapOf(
                    "stage" to "ALERT",
                    "order" to (index + 1),
                    "moduleVersionId" to alertVersion.id,
                    "moduleName" to alertVersion.module?.name,
                    "success" to false,
                    "stdout" to trimLog(e.stdout),
                    "stderr" to trimLog(e.stderr),
                    "exitCode" to e.exitCode,
                    "error" to e.message
                ))
                executionLog.add("FAIL ALERT #${index + 1} ${alertVersion.module?.name ?: alertVersion.id}: ${e.message}")
                throw e
            }
        }

        val alertPayload = data ?: inputData
        if (alertPayload != null) {
            val alertReport = alertDispatcher.evaluateRulesForPipeline(pipeline, alertPayload)
            results["alertsFired"] = alertReport.firedAlerts
            results["alertEvaluation"] = mapOf(
                "evaluatedRules" to alertReport.evaluatedRules,
                "matchedRules" to alertReport.matchedRules,
                "firedAlerts" to alertReport.firedAlerts,
                "cooldownSkipped" to alertReport.cooldownSkipped
            )
        }

        return mapOf(
            "result" to results,
            "moduleLogs" to moduleLogs,
            "executionLog" to executionLog
        )
    }
}
