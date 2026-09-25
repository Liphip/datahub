package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.alert.AlertDispatcher
import com.dataturntable.alert.AlertRuleConfig
import com.dataturntable.alert.AlertRuleEvaluator
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.service.PipelineDataService
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Defines the class `PipelineTriggerMessage`, the contract implemented by this source unit.
 */
data class PipelineTriggerMessage(
    val runId: String,
    val pipelineId: String,
    val triggerType: String,
    val data: Any? = null
)

/**
 * Defines the class `PipelineEvent`, the contract implemented by this source unit.
 */
data class PipelineEvent(
    val runId: String,
    val pipelineId: String,
    val stage: String,
    val success: Boolean,
    val result: Any? = null,
    val error: String? = null
)

@ApplicationScoped
/**
 * Defines the class `PipelineConsumer`, the contract implemented by this source unit.
 */
class PipelineConsumer {

    private val LOG = Logger.getLogger(PipelineConsumer::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * Implements `trimLog` for this source unit.
     */
    private fun trimLog(value: String, maxLength: Int = 8000): String {
        if (value.length <= maxLength) return value
        return value.take(maxLength) + "\n...<truncated>"
    }

    /** Strip the platform result sentinel line so only user print() output is shown in logs. */
    /**
     * Implements `userOutput` for this source unit.
     */
    private fun userOutput(stdout: String): String =
        stdout.lines().filter { !it.trim().startsWith("__RESULT__:") }.joinToString("\n").trim()

    @Inject
    lateinit var dockerService: DockerService

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @Inject
    lateinit var alertRuleEvaluator: AlertRuleEvaluator

    @Inject
    lateinit var connectorRuntime: ConnectorRuntimeService

    @Inject
    lateinit var logWriter: PipelineLogWriter

    @Inject
    lateinit var meterRegistry: MeterRegistry

    @Inject
    lateinit var metrics: PipelineMetrics


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

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseJsonMap(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(raw, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Implements `resolveConnectorsByNodeId` for this source unit.
     */
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

            // Full platform-side config incl. credentials (used for actual fetch/deliver, never sent to modules)
            val decryptedConfig = connectorRuntime.resolveRuntimeConfig(connector)

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
     * Executes one queued run (called by [PipelineInputListener] for every Kafka record).
     *
     * The outer @Transactional provides a persistence context for reads (Pipeline, ModuleVersion,
     * Connector lookups). All writes go through [PipelineLogWriter] in REQUIRES_NEW transactions
     * so the frontend can poll progressive state while this method is still running.
     *
     * Failures of the pipeline itself are recorded on the run (with its input, so it can be
     * retried) and do not throw. Exceptions that escape are infrastructure failures (database
     * unreachable, e.g. during a failover); the listener retries them and does not commit the
     * Kafka offset, so the record is not lost.
     */
    @Transactional
    fun consume(message: String): PipelineEvent {
        val trigger = try {
            val tree = objectMapper.readTree(message)
            PipelineTriggerMessage(
                runId = tree.path("runId").asText(),
                pipelineId = tree.path("pipelineId").asText(),
                triggerType = tree.path("triggerType").asText(),
                data = tree.get("data")?.takeIf { it.isObject }?.let { objectMapper.convertValue(it, Map::class.java) },
            )
        } catch (e: Exception) {
            LOG.errorf("Discarding unparseable pipeline message: %s", e.message)
            return PipelineEvent("unknown", "unknown", "parse", false, error = e.message)
        }
        val pipelineId = trigger.pipelineId
        val runId = trigger.runId
        @Suppress("UNCHECKED_CAST")
        val dataPayload = trigger.data as? Map<String, Any>
        val pipelineUuid = parseUuid(pipelineId)
            ?: return PipelineEvent(runId, pipelineId, "init", false, error = "Invalid pipeline ID")
        val runUuid = parseUuid(runId)
            ?: return PipelineEvent(runId, pipelineId, "init", false, error = "Invalid run ID")

        val pipeline = Pipeline.findById(pipelineUuid)
        if (pipeline == null) {
            LOG.errorf("Pipeline not found: %s", pipelineId)
            logWriter.markFailed(runUuid, "Pipeline not found", null)
            return PipelineEvent(runId, pipelineId, "init", false, error = "Pipeline not found")
        }

        // A record is redelivered when an executor stopped before committing its offset.
        // Finished runs are skipped; a run that was RUNNING on the lost executor starts again.
        if (!logWriter.claimRun(runUuid)) {
            LOG.infof("Run %s is already finished or cancelled, skipping redelivered record", runId)
            return PipelineEvent(runId, pipelineId, "skipped", false, error = "Run already finished")
        }
        LOG.debugf("Executing run %s of pipeline %s", runId, pipelineId)

        val executionStartedAt = System.currentTimeMillis()

        try {
            val result = executePipeline(pipeline, runUuid, runId, dataPayload)
            val executionMs = System.currentTimeMillis() - executionStartedAt

            // Record Prometheus metrics (histogram timers via PipelineMetrics for _bucket support)
            metrics.pipelineCounter("SUCCESS", pipeline.name ?: pipelineId).increment()
            metrics.pipelineTimer(pipeline.name ?: pipelineId, "SUCCESS")
                .record(executionMs, TimeUnit.MILLISECONDS)

            // Check for cancellation before committing success
            if (logWriter.isCancelled(runUuid)) {
                return PipelineEvent(
                    runId = runId,
                    pipelineId = pipelineId,
                    stage = "cancelled",
                    success = false,
                    error = "Run cancelled"
                )
            }

            logWriter.markSuccess(runUuid, objectMapper.writeValueAsString(
                mapOf(
                    "executionMs" to executionMs,
                    "result" to result["result"],
                    "moduleLogs" to result["moduleLogs"],
                    "executionLog" to result["executionLog"]
                )
            ))

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

            if (logWriter.isCancelled(runUuid)) {
                return PipelineEvent(
                    runId = runId,
                    pipelineId = pipelineId,
                    stage = "cancelled",
                    success = false,
                    error = "Run cancelled"
                )
            }

            // Lost database connections (e.g. a PostgreSQL failover) are not a pipeline error:
            // rethrow so the record is redelivered once the database is back.
            if (isTransientInfrastructureFailure(e)) throw e

            val moduleException = e as? ModuleExecutionException
            logWriter.markFailed(runUuid, e.message, input = objectMapper.writeValueAsString(dataPayload), metrics = objectMapper.writeValueAsString(
                mapOf(
                    "executionMs" to executionMs,
                    "error" to (e.message ?: "Pipeline execution failed"),
                    "stdout" to (moduleException?.stdout?.let { trimLog(it) }),
                    "stderr" to (moduleException?.stderr?.let { trimLog(it) }),
                    "exitCode" to moduleException?.exitCode
                )
            ))

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

    /**
     * Processes the named request, event, or operation.
     */
    private fun executePipeline(pipeline: Pipeline, runUuid: UUID?, runId: String, inputData: Map<String, Any>?): Map<String, Any?> {
        val results = mutableMapOf<String, Any?>()
        val moduleLogs = mutableListOf<Map<String, Any?>>()
        val executionLog = mutableListOf<String>()
        val connectorsByNodeId = resolveConnectorsByNodeId(pipeline)
        val pipelineId = pipeline.id.toString()

        /**
         * Defines the class `GraphNode`, the contract implemented by this source unit.
         */
        data class GraphNode(
            val id: String,
            val type: ModuleType,
            /** Null only for ALERT nodes that use a built-in rule. */
            val moduleVersionId: UUID?,
            val connector: ResolvedConnector?,
            /** ALERT nodes: default channel (id or name) when an alert names none. */
            val alertChannel: String? = null,
            /** ALERT nodes: built-in rule evaluated by the platform instead of a module. */
            val alertRule: AlertRuleConfig? = null,
        )

        /** Evaluates a built-in alert rule node and dispatches its alerts. */
        fun executeRuleNode(node: GraphNode, rule: AlertRuleConfig, input: Any?, order: Int): Any? {
            val stage = "ALERT"
            val label = "rule:${rule.type.key}"
            val startedAt = System.currentTimeMillis()
            executionLog.add("START $stage #$order $label [${node.id}]")
            val alerts = alertRuleEvaluator.evaluate(pipeline.id!!, node.id, rule, input)
            alerts.forEach { alert ->
                try {
                    alertDispatcher.dispatchRuleAlert(pipeline, node.id, node.alertChannel, rule, alert)
                } catch (e: Exception) {
                    LOG.warnf("Alert dispatch failed for node %s: %s", node.id, e.message)
                }
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val summary = if (alerts.isEmpty()) "No alert raised by ${rule.type.key} rule"
                else alerts.joinToString("\n") { "Alert: ${it.title} — ${it.message}" }
            if (runUuid != null) {
                logWriter.persistNodeLog(
                    runId = runUuid,
                    nodeId = node.id,
                    nodeType = stage,
                    moduleName = label,
                    success = true,
                    stdout = summary,
                    stderr = "",
                    errorMessage = null,
                    durationMs = durationMs,
                )
            }
            metrics.moduleCounter(stage, "SUCCESS").increment()
            metrics.moduleTimer(stage, "SUCCESS").record(durationMs, TimeUnit.MILLISECONDS)
            val result = mapOf("alert_fired" to alerts.isNotEmpty(), "alerts" to alerts.size)
            moduleLogs.add(mapOf(
                "stage" to stage,
                "order" to order,
                "nodeId" to node.id,
                "moduleName" to label,
                "success" to true,
                "stdout" to summary,
                "stderr" to "",
                "exitCode" to 0,
                "durationMs" to durationMs,
                "result" to result,
            ))
            executionLog.add("DONE $stage #$order $label [${node.id}] (${durationMs}ms)")
            return result
        }

        /**
         * Execute a single pipeline node.
         *
         * IMPORT  — platform fetches from connector when no inputData exists, then module parses.
         * MERGE   — receives a List of upstream outputs; different class/method signature.
         * EXPORT  — module shapes data, then platform delivers via connector.
         * PROCESSING / ALERT — standard single-input execution.
         */
        fun executeNode(node: GraphNode, moduleInput: Any?, order: Int): Any? {
            node.alertRule?.let { return executeRuleNode(node, it, moduleInput, order) }
            val version = node.moduleVersionId?.let { ModuleVersion.findById(it) }
                ?: throw IllegalStateException("Missing module version for node ${node.id}")
            // DB_LOAD/DB_SAVE modules sit on IMPORT/EXPORT nodes; execution follows the module's own type.
            val execType = version.module?.moduleType ?: node.type
            val stage = node.type.name
            LOG.infof("Executing %s node %s: %s", stage, node.id, version.module?.name)
            executionLog.add("START $stage #$order ${version.module?.name ?: version.id} [${node.id}]")

            val nodeStartedAt = System.currentTimeMillis()

            // For IMPORT with no upstream data: platform fetches from connector before calling module.
            val resolvedInput: Any? = if (execType == ModuleType.IMPORT && moduleInput == null) {
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
                val output = if (execType == ModuleType.DB_LOAD || execType == ModuleType.DB_SAVE) {
                    val tenantId = pipeline.tenant?.id
                        ?: throw IllegalStateException("Pipeline tenant missing for ${node.type}")
                    val dbOutput = pipelineDataService.execute(
                        moduleVersion = version,
                        moduleType = execType,
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
                        execType,
                        node.connector?.meta
                    )
                }

                // For EXPORT: platform delivers shaped result via connector after module runs.
                if (execType == ModuleType.EXPORT) {
                    val conn = node.connector
                    if (conn != null && output.result != null) {
                        LOG.infof("EXPORT node %s: platform delivering via connector %s (%s)",
                            node.id, conn.connector.name, conn.connector.connectorType)
                        // A failed delivery fails the run (which keeps its input for Retry)
                        // instead of silently dropping the data.
                        try {
                            connectorRuntime.deliverViaConnector(conn.connector, conn.config, output.result)
                        } catch (e: Exception) {
                            throw IllegalStateException(
                                "Delivery via connector '${conn.connector.name}' failed: ${e.message}", e)
                        }
                    }
                }

                // Dispatch alerts collected by alert_fn() in the ALERT module
                if (execType == ModuleType.ALERT) {
                    val alerts = (output.result as? Map<*, *>)?.get("alerts") as? List<*> ?: emptyList<Any>()
                    alerts.filterIsInstance<Map<*, *>>().forEach { alert ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            alertDispatcher.dispatchToChannel(
                                pipeline = pipeline,
                                channelRef = alert["channel"]?.toString()?.takeIf { it.isNotBlank() } ?: node.alertChannel,
                                title = alert["title"]?.toString()?.takeIf { it.isNotBlank() }
                                    ?: "${pipeline.name ?: "Pipeline"}: ${version.module?.name ?: node.id}",
                                message = alert["message"]?.toString() ?: "",
                                severity = alert["severity"]?.toString() ?: "WARNING",
                                payload = alert["payload"] as? Map<String, Any>,
                                dedupKey = "${pipeline.id}:${node.id}:${alert["title"] ?: ""}",
                                cooldownSeconds = (alert["cooldownSeconds"] as? Number)?.toLong(),
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
            val alertRule = (nodeMap["alertRule"] as? Map<*, *>)?.takeIf { nodeType == ModuleType.ALERT }?.let {
                try {
                    AlertRuleConfig.parse(it)
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("Node $nodeId: ${e.message}")
                }
            }
            val moduleVersionId = parseUuid(nodeMap["moduleVersionId"]?.toString())
            if (moduleVersionId == null && alertRule == null) return@mapNotNull null
            GraphNode(
                id = nodeId,
                type = nodeType,
                moduleVersionId = moduleVersionId,
                connector = connectorsByNodeId[nodeId],
                alertChannel = nodeMap["alertChannel"]?.toString()?.takeIf { it.isNotBlank() },
                alertRule = alertRule,
            )
        }

        val nodeById = graphNodes.associateBy { it.id }
        val indegree   = nodeById.keys.associateWith { 0 }.toMutableMap()
        val upstream   = nodeById.keys.associateWith { mutableListOf<String>() }.toMutableMap()
        val downstream = nodeById.keys.associateWith { mutableListOf<String>() }.toMutableMap()

        graphEdgesRaw.forEach { rawEdge ->
            val edgeMap = rawEdge as? Map<*, *> ?: return@forEach
            val from = edgeMap["source"]?.toString()?.trim().orEmpty()
            val to   = edgeMap["target"]?.toString()?.trim().orEmpty()
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

        // Alerts are dispatched inside ALERT module nodes from the alerts collected by alert_fn().
        // No separate rule evaluation pass is needed here.

        return mapOf(
            "result" to results,
            "moduleLogs" to moduleLogs,
            "executionLog" to executionLog
        )
    }
}
