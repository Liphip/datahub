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
            val retryOfRunId = parseUuid(dataPayload?.get("_retryOfRunId")?.toString())
            if (retryOfRunId != null && run.retryOfRunId == null) {
                run.retryOfRunId = retryOfRunId
            }
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
        val pipelineId = pipeline.id.toString()

        data class GraphNode(
            val id: String,
            val type: ModuleType,
            val moduleVersionId: UUID,
            val connector: ResolvedConnector?
        )

        fun executeNode(
            node: GraphNode,
            moduleInput: Any?,
            order: Int
        ): Any? {
            val version = ModuleVersion.findById(node.moduleVersionId)
                ?: throw IllegalStateException("Missing module version for node ${node.id}")
            val stage = node.type.name
            LOG.infof("Executing %s node %s: %s", stage, node.id, version.module?.name)
            executionLog.add("START $stage #$order ${version.module?.name ?: version.id} [${node.id}]")
            try {
                val output = dockerService.executeModule(
                    version,
                    moduleInput,
                    pipelineId,
                    runId,
                    node.type,
                    node.connector?.config
                )
                moduleLogs.add(
                    mapOf(
                        "stage" to stage,
                        "order" to order,
                        "nodeId" to node.id,
                        "moduleVersionId" to version.id,
                        "moduleName" to version.module?.name,
                        "success" to true,
                        "stdout" to trimLog(output.stdout),
                        "stderr" to trimLog(output.stderr),
                        "exitCode" to output.exitCode,
                        "durationMs" to output.durationMs,
                        "connector" to node.connector?.summary
                    )
                )
                executionLog.add("DONE $stage #$order ${version.module?.name ?: version.id} [${node.id}] (${output.durationMs}ms)")
                return output.result
            } catch (e: ModuleExecutionException) {
                moduleLogs.add(
                    mapOf(
                        "stage" to stage,
                        "order" to order,
                        "nodeId" to node.id,
                        "moduleVersionId" to version.id,
                        "moduleName" to version.module?.name,
                        "success" to false,
                        "stdout" to trimLog(e.stdout),
                        "stderr" to trimLog(e.stderr),
                        "exitCode" to e.exitCode,
                        "error" to e.message,
                        "connector" to node.connector?.summary
                    )
                )
                executionLog.add("FAIL $stage #$order ${version.module?.name ?: version.id} [${node.id}]: ${e.message}")
                throw e
            }
        }

        val graph = parseJsonMap(pipeline.graphDefinition)
        val graphNodesRaw = graph["nodes"] as? List<*>
        val graphEdgesRaw = graph["edges"] as? List<*>

        if (!graphNodesRaw.isNullOrEmpty()) {
            val graphNodes = graphNodesRaw.mapNotNull { raw ->
                val nodeMap = raw as? Map<*, *> ?: return@mapNotNull null
                val nodeId = nodeMap["id"]?.toString()?.trim().orEmpty()
                if (nodeId.isBlank()) return@mapNotNull null
                val nodeType = try {
                    ModuleType.valueOf(nodeMap["type"]?.toString()?.uppercase() ?: return@mapNotNull null)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val moduleVersionId = parseUuid(nodeMap["moduleVersionId"]?.toString()) ?: return@mapNotNull null
                GraphNode(
                    id = nodeId,
                    type = nodeType,
                    moduleVersionId = moduleVersionId,
                    connector = connectorConfigsByVersion[moduleVersionId]
                )
            }
            val nodeById = graphNodes.associateBy { it.id }
            val indegree = mutableMapOf<String, Int>()
            val upstream = mutableMapOf<String, MutableList<String>>()
            val downstream = mutableMapOf<String, MutableList<String>>()
            nodeById.keys.forEach { nodeId ->
                indegree[nodeId] = 0
                upstream[nodeId] = mutableListOf()
                downstream[nodeId] = mutableListOf()
            }

            graphEdgesRaw.orEmpty().forEach { rawEdge ->
                val edgeMap = rawEdge as? Map<*, *> ?: return@forEach
                val from = edgeMap["from"]?.toString()?.trim().orEmpty()
                val to = edgeMap["to"]?.toString()?.trim().orEmpty()
                if (from !in nodeById || to !in nodeById) return@forEach
                downstream[from]?.add(to)
                upstream[to]?.add(from)
                indegree[to] = (indegree[to] ?: 0) + 1
            }

            val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
            val topologicalOrder = mutableListOf<String>()
            while (queue.isNotEmpty()) {
                val nodeId = queue.removeFirst()
                topologicalOrder.add(nodeId)
                downstream[nodeId].orEmpty().forEach { next ->
                    val updated = (indegree[next] ?: 0) - 1
                    indegree[next] = updated
                    if (updated == 0) queue.add(next)
                }
            }
            val orderedNodeIds = if (topologicalOrder.size == nodeById.size) topologicalOrder else nodeById.keys.toList()

            val outputs = mutableMapOf<String, Any?>()
            orderedNodeIds.forEachIndexed { index, nodeId ->
                val node = nodeById[nodeId] ?: return@forEachIndexed
                val inputCandidates = upstream[nodeId].orEmpty().mapNotNull { parentId -> outputs[parentId] }
                val nodeInput: Any? = when {
                    inputCandidates.isEmpty() && node.type == ModuleType.IMPORT -> inputData
                    inputCandidates.isEmpty() -> null
                    inputCandidates.size == 1 -> inputCandidates.first()
                    else -> mapOf("inputs" to inputCandidates)
                }
                if (nodeInput == null && node.type != ModuleType.IMPORT) {
                    executionLog.add("SKIP ${node.type.name} #${index + 1} ${node.id} (no input)")
                    return@forEachIndexed
                }

                val output = executeNode(node, nodeInput, index + 1)
                outputs[node.id] = output
                results[node.id] = mapOf(
                    "type" to node.type.name,
                    "moduleVersionId" to node.moduleVersionId,
                    "result" to output
                )
            }

            val terminalPayloads = orderedNodeIds
                .filter { downstream[it].isNullOrEmpty() }
                .mapNotNull { outputs[it] }
            val alertPayload = when {
                terminalPayloads.isNotEmpty() -> terminalPayloads.last()
                outputs.isNotEmpty() -> outputs.values.lastOrNull()
                else -> inputData
            }

            if (alertPayload is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val alertMapPayload = alertPayload as? Map<String, Any>
                if (alertMapPayload != null) {
                    val alertReport = alertDispatcher.evaluateRulesForPipeline(pipeline, alertMapPayload)
                    results["alertsFired"] = alertReport.firedAlerts
                    results["alertEvaluation"] = mapOf(
                        "evaluatedRules" to alertReport.evaluatedRules,
                        "matchedRules" to alertReport.matchedRules,
                        "firedAlerts" to alertReport.firedAlerts,
                        "cooldownSkipped" to alertReport.cooldownSkipped
                    )
                }
            }

            return mapOf(
                "result" to results,
                "moduleLogs" to moduleLogs,
                "executionLog" to executionLog
            )
        }

        var data = inputData
        pipeline.importModuleVersionId?.let { importId ->
            val importVersion = ModuleVersion.findById(importId)
            if (importVersion != null) {
                val connectorContext = importVersion.id?.let { connectorConfigsByVersion[it] }
                val output = executeNode(
                    GraphNode(
                        id = "import",
                        type = ModuleType.IMPORT,
                        moduleVersionId = importVersion.id!!,
                        connector = connectorContext
                    ),
                    data,
                    1
                )
                results["import"] = output
                if (output is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    data = output as? Map<String, Any>
                }
            }
        }

        pipeline.processingModuleVersionIds.forEachIndexed { index, processingId ->
            val processingVersion = ModuleVersion.findById(processingId)
            if (processingVersion != null && data != null) {
                val output = executeNode(
                    GraphNode(
                        id = "processing-${index + 1}",
                        type = ModuleType.PROCESSING,
                        moduleVersionId = processingVersion.id!!,
                        connector = null
                    ),
                    data,
                    index + 2
                )
                results["processing_${index + 1}"] = output
                if (output is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    data = output as? Map<String, Any>
                }
            }
        }

        pipeline.exportModuleVersionIds.forEachIndexed { index, exportId ->
            val exportVersion = ModuleVersion.findById(exportId)
            if (exportVersion != null && data != null) {
                val connectorContext = exportVersion.id?.let { connectorConfigsByVersion[it] }
                val output = executeNode(
                    GraphNode(
                        id = "export-${index + 1}",
                        type = ModuleType.EXPORT,
                        moduleVersionId = exportVersion.id!!,
                        connector = connectorContext
                    ),
                    data,
                    index + 2 + pipeline.processingModuleVersionIds.size
                )
                results["export_${index + 1}"] = output
            }
        }

        resolveAlertModuleVersionsFromGraph(pipeline).forEachIndexed { index, alertVersion ->
            if (data == null) return@forEachIndexed
            val output = executeNode(
                GraphNode(
                    id = "alert-${index + 1}",
                    type = ModuleType.ALERT,
                    moduleVersionId = alertVersion.id!!,
                    connector = null
                ),
                data,
                index + 2 + pipeline.processingModuleVersionIds.size + pipeline.exportModuleVersionIds.size
            )
            results["alert_${index + 1}"] = output
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
