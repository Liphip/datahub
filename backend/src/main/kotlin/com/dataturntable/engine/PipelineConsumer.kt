package com.dataturntable.engine

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.alert.AlertDispatcher
import com.dataturntable.connectors.BrokerCredential
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.security.EncryptionUtils
import com.dataturntable.service.PipelineDataService
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.reactive.messaging.annotations.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    /** Strip the platform result sentinel line so only user print() output is shown in logs. */
    private fun userOutput(stdout: String): String =
        stdout.lines().filter { !it.trim().startsWith("__RESULT__:") }.joinToString("\n").trim()

    @Inject
    lateinit var dockerService: DockerService

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @Inject
    lateinit var connectorRuntime: ConnectorRuntimeService

    @Inject
    lateinit var logWriter: PipelineLogWriter

    @Inject
    lateinit var meterRegistry: MeterRegistry

    @Inject
    lateinit var metrics: PipelineMetrics

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var pipelineDataService: PipelineDataService

    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Full connector context for a node: decrypted config for platform use + non-sensitive
     * metadata forwarded to the module container as CONNECTOR_META.
     */
    private data class ResolvedConnector(
        /** Decrypted config used by ConnectorRuntimeService to fetch/deliver. */
        val config: Map<String, Any?>,
        /** Non-sensitive subset forwarded to the module as CONNECTOR_META (no credentials). */
        val meta: Map<String, Any?>,
        val connector: Connector
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

    private fun resolveConnectorsByNodeId(pipeline: Pipeline): Map<String, ResolvedConnector> {
        val tenantId = pipeline.tenant?.id ?: return emptyMap()
        val graph = parseJsonMap(pipeline.graphDefinition)
        val nodes = graph["nodes"] as? List<*> ?: return emptyMap()
        val resolved = mutableMapOf<String, ResolvedConnector>()

        nodes.forEach { node ->
            val nodeMap = node as? Map<*, *> ?: return@forEach
            val nodeType = nodeMap["type"]?.toString()?.uppercase() ?: return@forEach
            if (nodeType != "IMPORT" && nodeType != "EXPORT") return@forEach

            val nodeId = nodeMap["id"]?.toString()?.trim() ?: return@forEach
            val connectorId = parseUuid(nodeMap["connectorId"]?.toString()) ?: return@forEach
            val connector = em.find(Connector::class.java, connectorId) ?: return@forEach
            if (connector.tenant?.id != tenantId) return@forEach

            // Decrypt full config (used by ConnectorRuntimeService for actual fetch/deliver)
            val decryptedConfig = connectorRuntime.decryptConfig(connector).toMutableMap()

            // Inject MQTT credentials if present (only into platform-side config, not module)
            if (connector.connectorType == Connector.ConnectorType.MQTT) {
                val brokerCredentialId = parseUuid(decryptedConfig["brokerCredentialId"]?.toString())
                if (brokerCredentialId != null) {
                    val credential = em.find(BrokerCredential::class.java, brokerCredentialId)
                    if (credential != null && credential.tenant?.id == tenantId && credential.isActive) {
                        decryptedConfig["username"] = credential.username
                        val password = credential.passwordEncrypted?.let {
                            try { EncryptionUtils.decrypt(it) } catch (_: Exception) { null }
                        }
                        if (!password.isNullOrBlank()) decryptedConfig["password"] = password
                        decryptedConfig["brokerCredentialName"] = credential.name
                    }
                }
            }

            // Non-sensitive metadata forwarded to module container as CONNECTOR_META.
            // Credentials (password, token, key) are explicitly excluded.
            val meta = mapOf(
                "id" to connector.id?.toString(),
                "name" to connector.name,
                "connectorType" to connector.connectorType?.name,
                "direction" to connector.direction?.name,
                "pollingEnabled" to connector.pollingEnabled,
                "broker" to decryptedConfig["broker"],
                "topic" to decryptedConfig["topic"],
                "baseUrl" to decryptedConfig["baseUrl"],
                "path" to decryptedConfig["path"],
                "method" to decryptedConfig["method"],
                "host" to decryptedConfig["host"],
                "port" to decryptedConfig["port"]
            ).filterValues { it != null }

            resolved[nodeId] = ResolvedConnector(
                config = decryptedConfig,
                meta = meta,
                connector = connector
            )
        }

        return resolved
    }

    // resolveAlertModuleVersionsFromGraph removed — alerts are now proper ALERT nodes in the graph
    // and execute via the normal topological order in executePipeline.

    /**
     * Main Kafka consumer for pipeline execution.
     *
     * The outer @Transactional provides a persistence context for reads (Pipeline, ModuleVersion,
     * Connector lookups). All writes go through [PipelineLogWriter] in REQUIRES_NEW transactions
     * so the frontend can poll progressive state while this method is still running.
     */
    @Incoming("pipeline-input")
    @Blocking
    @Transactional
    fun consume(message: String): PipelineEvent {
        if (!runtimeRoleConfig.ownsPipelineRuntime()) {
            return PipelineEvent(
                runId = "skipped",
                pipelineId = "skipped",
                stage = "ignored",
                success = false,
                error = "Pipeline consumer is disabled for role ${runtimeRoleConfig.role.name}"
            )
        }
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

        val runUuid = parseUuid(runId)

        // Set retry-of link if applicable
        if (runUuid != null) {
            val retryOfRunId = parseUuid(dataPayload?.get("_retryOfRunId")?.toString())
            if (retryOfRunId != null) {
                logWriter.setRetryOfRunId(runUuid, retryOfRunId)
            }
        }

        // Check for cancellation before starting
        if (runUuid != null && logWriter.isCancelled(runUuid)) {
            return PipelineEvent(
                runId = runId,
                pipelineId = pipelineId,
                stage = "cancelled",
                success = false,
                error = "Run cancelled"
            )
        }

        // Mark RUNNING — committed immediately so frontend sees it
        if (runUuid != null) {
            logWriter.markRunning(runUuid)
        }

        val executionStartedAt = System.currentTimeMillis()

        try {
            val result = executePipeline(pipeline, runUuid, runId, dataPayload)
            val executionMs = System.currentTimeMillis() - executionStartedAt

            // Record Prometheus metrics (histogram timers via PipelineMetrics for _bucket support)
            metrics.pipelineCounter("SUCCESS", pipeline.name ?: pipelineId).increment()
            metrics.pipelineTimer(pipeline.name ?: pipelineId, "SUCCESS")
                .record(executionMs, TimeUnit.MILLISECONDS)

            // Check for cancellation before committing success
            if (runUuid != null && logWriter.isCancelled(runUuid)) {
                return PipelineEvent(
                    runId = runId,
                    pipelineId = pipelineId,
                    stage = "cancelled",
                    success = false,
                    error = "Run cancelled"
                )
            }

            if (runUuid != null) {
                logWriter.markSuccess(runUuid, objectMapper.writeValueAsString(
                    mapOf(
                        "executionMs" to executionMs,
                        "result" to result["result"],
                        "moduleLogs" to result["moduleLogs"],
                        "executionLog" to result["executionLog"]
                    )
                ))
            }

            // Structured log for Loki
            LOG.infof("PIPELINE_COMPLETE pipeline=%s run=%s status=SUCCESS duration_ms=%d modules=%d",
                pipelineId, runId, executionMs,
                (result["moduleLogs"] as? List<*>)?.size ?: 0)

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

            // Record Prometheus metrics (histogram timers via PipelineMetrics for _bucket support)
            metrics.pipelineCounter("FAILED", pipeline.name ?: pipelineId).increment()
            metrics.pipelineTimer(pipeline.name ?: pipelineId, "FAILED")
                .record(executionMs, TimeUnit.MILLISECONDS)

            if (runUuid != null && logWriter.isCancelled(runUuid)) {
                return PipelineEvent(
                    runId = runId,
                    pipelineId = pipelineId,
                    stage = "cancelled",
                    success = false,
                    error = "Run cancelled"
                )
            }

            val moduleException = e as? ModuleExecutionException
            if (runUuid != null) {
                logWriter.markFailed(runUuid, e.message, objectMapper.writeValueAsString(
                    mapOf(
                        "executionMs" to executionMs,
                        "error" to (e.message ?: "Pipeline execution failed"),
                        "stdout" to (moduleException?.stdout?.let { trimLog(it) }),
                        "stderr" to (moduleException?.stderr?.let { trimLog(it) }),
                        "exitCode" to moduleException?.exitCode
                    )
                ))
            }

            // Structured log for Loki
            LOG.errorf("PIPELINE_COMPLETE pipeline=%s run=%s status=FAILED duration_ms=%d error=%s",
                pipelineId, runId, executionMs, e.message?.take(200))

            return PipelineEvent(
                runId = runId,
                pipelineId = pipelineId,
                stage = "error",
                success = false,
                error = e.message
            )
        }
    }

    private fun executePipeline(pipeline: Pipeline, runUuid: UUID?, runId: String, inputData: Map<String, Any>?): Map<String, Any?> {
        val results = mutableMapOf<String, Any?>()
        val moduleLogs = mutableListOf<Map<String, Any?>>()
        val executionLog = mutableListOf<String>()
        val connectorsByNodeId = resolveConnectorsByNodeId(pipeline)
        val pipelineId = pipeline.id.toString()

        data class GraphNode(
            val id: String,
            val type: ModuleType,
            val moduleVersionId: UUID,
            val connector: ResolvedConnector?
        )

        /**
         * Execute a single pipeline node.
         *
         * IMPORT  — platform fetches from connector when no inputData exists, then module parses.
         * MERGE   — receives a List of upstream outputs; different class/method signature.
         * EXPORT  — module shapes data, then platform delivers via connector.
         * PROCESSING / ALERT — standard single-input execution.
         */
        fun executeNode(node: GraphNode, moduleInput: Any?, order: Int): Any? {
            val version = ModuleVersion.findById(node.moduleVersionId)
                ?: throw IllegalStateException("Missing module version for node ${node.id}")
            val stage = node.type.name
            LOG.infof("Executing %s node %s: %s", stage, node.id, version.module?.name)
            executionLog.add("START $stage #$order ${version.module?.name ?: version.id} [${node.id}]")

            val nodeStartedAt = System.currentTimeMillis()

            // For IMPORT with no upstream data: platform fetches from connector before calling module.
            val resolvedInput: Any? = if (node.type == ModuleType.IMPORT && moduleInput == null) {
                val conn = node.connector
                if (conn != null) {
                    LOG.infof("IMPORT node %s: platform fetching from connector %s (%s)",
                        node.id, conn.connector.name, conn.connector.connectorType)
                    try {
                        connectorRuntime.fetchFromConnector(conn.connector, conn.config) ?: emptyMap<String, Any>()
                    } catch (e: Exception) {
                        LOG.warnf("Connector fetch failed for node %s: %s — using empty input", node.id, e.message)
                        emptyMap<String, Any>()
                    }
                } else emptyMap<String, Any>()
            } else {
                moduleInput
            }

            try {
                val output = if (node.type == ModuleType.DB_LOAD || node.type == ModuleType.DB_SAVE) {
                    val tenantId = pipeline.tenant?.id
                        ?: throw IllegalStateException("Pipeline tenant missing for ${node.type}")
                    val dbOutput = pipelineDataService.execute(
                        moduleVersion = version,
                        moduleType = node.type,
                        inputData = resolvedInput,
                        tenantId = tenantId
                    )
                    ModuleExecutionOutput(
                        result = dbOutput.result,
                        stdout = dbOutput.stdout,
                        stderr = dbOutput.stderr,
                        exitCode = 0,
                        durationMs = dbOutput.durationMs
                    )
                } else {
                    dockerService.executeModule(
                        version,
                        resolvedInput,
                        pipelineId,
                        runId,
                        node.type,
                        node.connector?.meta
                    )
                }

                // For EXPORT: platform delivers shaped result via connector after module runs.
                if (node.type == ModuleType.EXPORT) {
                    val conn = node.connector
                    if (conn != null && output.result != null) {
                        LOG.infof("EXPORT node %s: platform delivering via connector %s (%s)",
                            node.id, conn.connector.name, conn.connector.connectorType)
                        try {
                            connectorRuntime.deliverViaConnector(conn.connector, conn.config, output.result)
                        } catch (e: Exception) {
                            LOG.warnf("Connector deliver failed for node %s: %s", node.id, e.message)
                        }
                    }
                }

                // Dispatch channel alert if ALERT module returned __alert__ key
                if (node.type == ModuleType.ALERT) {
                    val resultMap = output.result as? Map<*, *>
                    val alertPayload = resultMap?.get("__alert__") as? Map<*, *>
                    if (alertPayload != null) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            alertDispatcher.dispatchToChannel(
                                pipeline = pipeline,
                                channelRef = alertPayload["channelId"]?.toString()
                                    ?: alertPayload["channelName"]?.toString(),
                                title = alertPayload["title"]?.toString() ?: "Pipeline Alert",
                                message = alertPayload["message"]?.toString() ?: "",
                                severity = alertPayload["severity"]?.toString() ?: "WARNING",
                                payload = alertPayload["payload"] as? Map<String, Any>
                            )
                        } catch (ae: Exception) {
                            LOG.warnf("Alert dispatch failed for node %s: %s", node.id, ae.message)
                        }
                    }
                }

                val userStdout = trimLog(userOutput(output.stdout))
                val trimmedStderr = trimLog(output.stderr, 4000)

                // Persist node log immediately in its own transaction
                if (runUuid != null) {
                    logWriter.persistNodeLog(
                        runId = runUuid,
                        nodeId = node.id,
                        nodeType = stage,
                        moduleName = version.module?.name,
                        success = true,
                        stdout = userStdout,
                        stderr = trimmedStderr,
                        errorMessage = null,
                        durationMs = output.durationMs,
                    )
                }

                // Record module-level Prometheus metrics (histogram timers for _bucket support)
                metrics.moduleCounter(stage, "SUCCESS").increment()
                metrics.moduleTimer(stage, "SUCCESS")
                    .record(output.durationMs, TimeUnit.MILLISECONDS)

                // Structured log for Loki: module execution
                LOG.infof("MODULE_EXEC pipeline=%s run=%s node=%s stage=%s module=%s status=SUCCESS duration_ms=%d stdout_len=%d stderr_len=%d",
                    pipelineId, runId, node.id, stage, version.module?.name,
                    output.durationMs, userStdout.length, trimmedStderr.length)

                moduleLogs.add(mapOf(
                    "stage" to stage,
                    "order" to order,
                    "nodeId" to node.id,
                    "moduleVersionId" to version.id,
                    "moduleName" to version.module?.name,
                    "success" to true,
                    "stdout" to userStdout,
                    "stderr" to trimmedStderr,
                    "exitCode" to output.exitCode,
                    "durationMs" to output.durationMs,
                    "result" to output.result,
                    "connector" to node.connector?.meta
                ))
                executionLog.add("DONE $stage #$order ${version.module?.name ?: version.id} [${node.id}] (${output.durationMs}ms)")
                return output.result

            } catch (e: ModuleExecutionException) {
                val nodeDurationMs = System.currentTimeMillis() - nodeStartedAt
                val userStdout = trimLog(userOutput(e.stdout))
                val trimmedStderr = trimLog(e.stderr, 4000)

                // Persist node log immediately in its own transaction
                if (runUuid != null) {
                    logWriter.persistNodeLog(
                        runId = runUuid,
                        nodeId = node.id,
                        nodeType = stage,
                        moduleName = version.module?.name,
                        success = false,
                        stdout = userStdout,
                        stderr = trimmedStderr,
                        errorMessage = e.message,
                        durationMs = nodeDurationMs,
                    )
                }

                // Record module-level Prometheus metrics (histogram timers for _bucket support)
                metrics.moduleCounter(stage, "FAILED").increment()
                metrics.moduleTimer(stage, "FAILED")
                    .record(nodeDurationMs, TimeUnit.MILLISECONDS)

                // Structured log for Loki: module failure with stdout/stderr
                LOG.errorf("MODULE_EXEC pipeline=%s run=%s node=%s stage=%s module=%s status=FAILED duration_ms=%d exit_code=%s error=%s",
                    pipelineId, runId, node.id, stage, version.module?.name,
                    nodeDurationMs, e.exitCode, e.message?.take(200))
                if (userStdout.isNotBlank()) {
                    LOG.infof("MODULE_STDOUT pipeline=%s run=%s node=%s stdout=%s",
                        pipelineId, runId, node.id, userStdout.take(2000))
                }
                if (trimmedStderr.isNotBlank()) {
                    LOG.warnf("MODULE_STDERR pipeline=%s run=%s node=%s stderr=%s",
                        pipelineId, runId, node.id, trimmedStderr.take(2000))
                }

                moduleLogs.add(mapOf(
                    "stage" to stage,
                    "order" to order,
                    "nodeId" to node.id,
                    "moduleVersionId" to version.id,
                    "moduleName" to version.module?.name,
                    "success" to false,
                    "stdout" to userStdout,
                    "stderr" to trimmedStderr,
                    "exitCode" to e.exitCode,
                    "durationMs" to nodeDurationMs,
                    "error" to e.message,
                    "connector" to node.connector?.meta
                ))
                executionLog.add("FAIL $stage #$order ${version.module?.name ?: version.id} [${node.id}]: ${e.message}")
                throw e
            }
        }

        // ── Parse graph ──────────────────────────────────────────────────────
        val graph = parseJsonMap(pipeline.graphDefinition)
        val graphNodesRaw = graph["nodes"] as? List<*> ?: emptyList<Any>()
        val graphEdgesRaw = graph["edges"] as? List<*> ?: emptyList<Any>()

        val graphNodes = graphNodesRaw.mapNotNull { raw ->
            val nodeMap = raw as? Map<*, *> ?: return@mapNotNull null
            val nodeId = nodeMap["id"]?.toString()?.trim().orEmpty().ifBlank { return@mapNotNull null }
            val nodeType = try {
                ModuleType.valueOf(nodeMap["type"]?.toString()?.uppercase() ?: return@mapNotNull null)
            } catch (_: Exception) { return@mapNotNull null }
            val moduleVersionId = parseUuid(nodeMap["moduleVersionId"]?.toString()) ?: return@mapNotNull null
            GraphNode(
                id = nodeId,
                type = nodeType,
                moduleVersionId = moduleVersionId,
                connector = connectorsByNodeId[nodeId]
            )
        }

        val nodeById = graphNodes.associateBy { it.id }
        val indegree   = nodeById.keys.associateWith { 0 }.toMutableMap()
        val upstream   = nodeById.keys.associateWith { mutableListOf<String>() }.toMutableMap()
        val downstream = nodeById.keys.associateWith { mutableListOf<String>() }.toMutableMap()

        graphEdgesRaw.forEach { rawEdge ->
            val edgeMap = rawEdge as? Map<*, *> ?: return@forEach
            // React Flow serialises as source/target; also accept legacy from/to
            val from = (edgeMap["source"] ?: edgeMap["from"])?.toString()?.trim().orEmpty()
            val to   = (edgeMap["target"] ?: edgeMap["to"])?.toString()?.trim().orEmpty()
            if (from !in nodeById || to !in nodeById) return@forEach
            downstream[from]?.add(to)
            upstream[to]?.add(from)
            indegree[to] = (indegree[to] ?: 0) + 1
        }

        // Kahn's topological sort (deterministic: sort keys for consistent ordering)
        val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys.sorted())
        val topologicalOrder = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            topologicalOrder.add(nodeId)
            downstream[nodeId].orEmpty().sorted().forEach { next ->
                val updated = (indegree[next] ?: 0) - 1
                indegree[next] = updated
                if (updated == 0) queue.add(next)
            }
        }
        val orderedNodeIds = if (topologicalOrder.size == nodeById.size) topologicalOrder else nodeById.keys.toList()

        // ── Execute in topological order ─────────────────────────────────────
        val outputs = mutableMapOf<String, Any?>()
        orderedNodeIds.forEachIndexed { index, nodeId ->
            val node = nodeById[nodeId] ?: return@forEachIndexed
            val upstreamOutputs = upstream[nodeId].orEmpty().mapNotNull { outputs[it] }

            val nodeInput: Any? = when (node.type) {
                ModuleType.IMPORT -> {
                    // Use injected data if present; otherwise connector fetch happens inside executeNode.
                    upstreamOutputs.firstOrNull() ?: inputData
                }
                ModuleType.MERGE -> {
                    // MERGE always receives a list — the module's merge() method gets the whole list.
                    upstreamOutputs.ifEmpty { null }
                }
                else -> when (upstreamOutputs.size) {
                    0    -> null
                    1    -> upstreamOutputs.first()
                    else -> upstreamOutputs.last()
                }
            }

            if (nodeInput == null && node.type !in setOf(ModuleType.IMPORT, ModuleType.MERGE)) {
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

        // Alerts are now dispatched inside ALERT module nodes via __alert__ in their output.
        // No separate rule evaluation pass is needed here.

        return mapOf(
            "result" to results,
            "moduleLogs" to moduleLogs,
            "executionLog" to executionLog
        )
    }
}
