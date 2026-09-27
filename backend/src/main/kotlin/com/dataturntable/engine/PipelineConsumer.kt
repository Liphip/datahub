package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.events.ConsumerDeadLetterService
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.security.SensitiveDataRedactor
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.alert.AlertDispatcher
import com.dataturntable.alert.AlertRuleConfig
import com.dataturntable.alert.AlertRuleEvaluator
import com.dataturntable.alert.AlertTarget
import com.dataturntable.metadata.EnrichConfig
import com.dataturntable.metadata.MetadataNodes
import com.dataturntable.metadata.SaveMetadataConfig
import io.quarkus.narayana.jta.QuarkusTransaction
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
    val data: Any? = null,
    /** ISO-8601 time the record was handed to Kafka. */
    val queuedAt: String? = null,
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

    private companion object {
        /** Metadata the platform adds to queued records; not part of the device data. */
        val PLATFORM_KEYS = setOf("_pipelineId", "_runId", "_ingestedAt", "_tenantId", "_connectorId",
            "_mqttTopic", "_triggerType", "_source", "_retryOfRunId")
    }

    private val LOG = Logger.getLogger(PipelineConsumer::class.java)
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

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

    @Inject
    lateinit var metadataNodes: MetadataNodes

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    /** Poison messages (unparseable, unknown target) are kept for inspection. */
    @Inject
    lateinit var deadLetters: ConsumerDeadLetterService

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
    private fun deadLetter(message: String, runId: String, pipelineId: String, reason: String): PipelineEvent {
        LOG.errorf("Run %s of pipeline %s moved to the dead letters: %s", runId, pipelineId, reason)
        deadLetters.record("pipeline-input", message, reason)
        return PipelineEvent(runId, pipelineId, "init", false, error = reason)
    }

    /**
     * Graph a run executes: the immutable snapshot of the version the run is pinned to, so an
     * edit made while the record waited in the queue does not change what runs. Pipelines
     * without versions execute their current graph.
     */
    internal fun resolveExecutionGraphJson(pipeline: Pipeline, runUuid: UUID?): String {
        val pinnedVersion = runUuid?.let { em.find(PipelineRun::class.java, it) }?.pipelineVersion
        return pinnedVersion?.graphDefinition ?: pipeline.graphDefinition ?: "{}"
    }

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
    private fun resolveConnectorsByNodeId(pipeline: Pipeline, graphJson: String): Map<String, ResolvedConnector> {
        val tenantId = pipeline.tenant?.id ?: return emptyMap()
        val graph = parseJsonMap(graphJson)
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
    fun consume(message: String, source: PipelineLogWriter.RecordSource? = null): PipelineEvent {
        val trigger = try {
            val tree = objectMapper.readTree(message)
            PipelineTriggerMessage(
                runId = tree.path("runId").asText(),
                pipelineId = tree.path("pipelineId").asText(),
                triggerType = tree.path("triggerType").asText(),
                data = tree.get("data")?.takeIf { it.isObject }?.let { objectMapper.convertValue(it, Map::class.java) },
                queuedAt = tree.path("queuedAt").asText(null),
            )
        } catch (e: Exception) {
            LOG.errorf("Unparseable pipeline message moved to the dead letters: %s", redactor.text(e.message))
            deadLetters.record("pipeline-input", message, "Invalid JSON: ${e.message}")
            return PipelineEvent("unknown", "unknown", "parse", false, error = e.message)
        }
        val pipelineId = trigger.pipelineId
        val runId = trigger.runId
        @Suppress("UNCHECKED_CAST")
        val dataPayload = trigger.data as? Map<String, Any>
        val pipelineUuid = parseUuid(pipelineId)
            ?: return deadLetter(message, runId, pipelineId, "Invalid pipeline ID")
        val runUuid = parseUuid(runId)
            ?: return deadLetter(message, runId, pipelineId, "Invalid run ID")

        val pipeline = Pipeline.findById(pipelineUuid)
        if (pipeline == null) {
            // Deleted pipeline: nothing to run it against; kept for inspection.
            return deadLetter(message, runId, pipelineId, "Pipeline not found")
        }

        // Exactly once: the run row is the record of what happened. Finished runs and second
        // copies of a run (client resend with the same idempotency key) are skipped; the
        // redelivery of a record whose executor died re-runs it.
        val claimed = logWriter.claimRun(
            runId = runUuid,
            pipelineId = pipelineUuid,
            triggerType = runCatching { TriggerType.valueOf(trigger.triggerType) }.getOrDefault(TriggerType.DATA),
            queuedAt = trigger.queuedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
            retryOfRunId = parseUuid(dataPayload?.get("_retryOfRunId")?.toString()),
            pipelineVersionId = parseUuid(dataPayload?.get("_pipelineVersionId")?.toString()),
            source = source,
        )
        if (!claimed) {
            LOG.infof("Run %s already finished or claimed by another copy, skipping record", runId)
            return PipelineEvent(runId, pipelineId, "skipped", false, error = "Run already processed")
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
            LOG.errorf("Pipeline execution failed: %s", redactor.text(e.message))
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
        val graphJson = resolveExecutionGraphJson(pipeline, runUuid)
        val connectorsByNodeId = resolveConnectorsByNodeId(pipeline, graphJson)
        val pipelineId = pipeline.id.toString()

        /**
         * Defines the class `GraphNode`, the contract implemented by this source unit.
         */
        data class GraphNode(
            val id: String,
            val type: ModuleType,
            /** Null for ALERT nodes with a built-in rule and for pass-through IMPORT nodes. */
            val moduleVersionId: UUID?,
            val connector: ResolvedConnector?,
            /** ALERT nodes: default channel (id or name) when an alert names none. */
            val alertChannel: String? = null,
            /** ALERT nodes: built-in rule evaluated by the platform instead of a module. */
            val alertRule: AlertRuleConfig? = null,
            /** PROCESSING nodes: built-in metadata enrichment instead of a module. */
            val enrich: EnrichConfig? = null,
            /** EXPORT nodes: records are saved as metadata objects instead of exported. */
            val saveMetadata: SaveMetadataConfig? = null,
        )

        /** Log entry of a built-in node (no container). */
        fun builtinLog(node: GraphNode, label: String, order: Int, startedAt: Long, summary: String) {
            val durationMs = System.currentTimeMillis() - startedAt
            if (runUuid != null) {
                logWriter.persistNodeLog(
                    runId = runUuid, nodeId = node.id, nodeType = node.type.name, moduleName = label,
                    success = true, stdout = summary, stderr = "", errorMessage = null, durationMs = durationMs,
                )
            }
            moduleLogs.add(mapOf("stage" to node.type.name, "order" to order, "nodeId" to node.id, "moduleName" to label,
                "success" to true, "stdout" to summary, "durationMs" to durationMs))
            executionLog.add("DONE ${node.type.name} #$order $label [${node.id}] (${durationMs}ms)")
        }

        fun executeEnrich(node: GraphNode, config: EnrichConfig, input: Any?, order: Int): Any? {
            val startedAt = System.currentTimeMillis()
            executionLog.add("START PROCESSING #$order enrich [${node.id}]")
            val tenantId = pipeline.tenant?.id ?: throw IllegalStateException("Pipeline tenant missing")
            val result = metadataNodes.enrich(tenantId, config, input)
            builtinLog(node, "enrich:${config.scheme}", order, startedAt,
                "Enriched ${result.enriched}, no metadata for ${result.missing}" + if (result.dropped > 0) " (dropped ${result.dropped})" else "")
            return result.output
        }

        fun executeSaveMetadata(node: GraphNode, config: SaveMetadataConfig, input: Any?, order: Int): Any? {
            val startedAt = System.currentTimeMillis()
            executionLog.add("START EXPORT #$order save-metadata [${node.id}]")
            val tenantId = pipeline.tenant?.id ?: throw IllegalStateException("Pipeline tenant missing")
            val result = metadataNodes.save(tenantId, config, input, source = "pipeline:${pipeline.id}")
            builtinLog(node, "save-metadata:${config.typeKey}", order, startedAt,
                "Created ${result.created}, changed ${result.changed}, unchanged ${result.unchanged}")
            return mapOf("created" to result.created, "changed" to result.changed, "unchanged" to result.unchanged)
        }

        /** Evaluates a built-in alert rule node and dispatches its alerts. */
        fun executeRuleNode(node: GraphNode, rule: AlertRuleConfig, input: Any?, order: Int): Any? {
            val stage = "ALERT"
            val label = "rule:${rule.type.key}"
            val startedAt = System.currentTimeMillis()
            executionLog.add("START $stage #$order $label [${node.id}]")
            // State change and outbox entries commit together; delivery failures are retried
            // by the outbox, so they do not fail the run.
            val alerts = alertRuleEvaluator.evaluate(pipeline.id!!, node.id, rule, input,
                target = AlertTarget(node.alertChannel, runUuid))
            alerts.mapNotNull { it.alertId }.forEach { alertDispatcher.deliver(it) }
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
        /**
         * Built-in IMPORT without a module: emits the received record. Connector deliveries
         * ({"payload": ..., "_connectorId": ...}) are unwrapped to their payload and platform
         * metadata keys are removed, so rules and sinks see the device's own fields.
         */
        fun executePassThrough(node: GraphNode, input: Any?, order: Int): Any? {
            val startedAt = System.currentTimeMillis()
            executionLog.add("START IMPORT #$order pass-through [${node.id}]")
            val received = input ?: node.connector?.let { connectorRuntime.fetchFromConnector(it.connector, it.config) }
            val record = when {
                received is Map<*, *> && received.containsKey("payload") && received.containsKey("_connectorId") -> received["payload"]
                received is Map<*, *> -> received.filterKeys { it.toString() !in PLATFORM_KEYS }
                else -> received
            }
            val durationMs = System.currentTimeMillis() - startedAt
            if (runUuid != null) {
                logWriter.persistNodeLog(
                    runId = runUuid, nodeId = node.id, nodeType = "IMPORT", moduleName = "pass-through",
                    success = true, stdout = "", stderr = "", errorMessage = null, durationMs = durationMs,
                )
            }
            moduleLogs.add(mapOf("stage" to "IMPORT", "order" to order, "nodeId" to node.id, "moduleName" to "pass-through",
                "success" to true, "durationMs" to durationMs))
            executionLog.add("DONE IMPORT #$order pass-through [${node.id}] (${durationMs}ms)")
            return record
        }

        fun executeNode(node: GraphNode, moduleInput: Any?, order: Int): Any? {
            node.alertRule?.let { return executeRuleNode(node, it, moduleInput, order) }
            if (node.type == ModuleType.IMPORT && node.moduleVersionId == null) return executePassThrough(node, moduleInput, order)
            node.enrich?.let { return executeEnrich(node, it, moduleInput, order) }
            node.saveMetadata?.let { return executeSaveMetadata(node, it, moduleInput, order) }
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
                        tenantId = tenantId,
                        recordKeyPrefix = runUuid?.let { "$it:${node.id}" },
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
                            connectorRuntime.deliverViaConnector(conn.connector, conn.config, output.result,
                                deliveryKey = runUuid?.let { "$it:${node.id}" })
                        } catch (e: Exception) {
                            throw IllegalStateException(
                                "Delivery via connector '${conn.connector.name}' failed: ${e.message}", e)
                        }
                    }
                }

                // Dispatch alerts collected by alert_fn() in the ALERT module
                if (execType == ModuleType.ALERT) {
                    val alerts = ((output.result as? Map<*, *>)?.get("alerts") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                    // Enqueued together and keyed by run, node and position: a run executed again
                    // after a failover finds its alerts already in the outbox.
                    val alertIds = QuarkusTransaction.requiringNew().call {
                        val managed = em.find(Pipeline::class.java, pipeline.id)
                        alerts.mapIndexedNotNull { index, alert ->
                            @Suppress("UNCHECKED_CAST")
                            alertDispatcher.enqueue(
                                pipeline = managed,
                                channelRef = alert["channel"]?.toString()?.takeIf { it.isNotBlank() } ?: node.alertChannel,
                                title = alert["title"]?.toString()?.takeIf { it.isNotBlank() }
                                    ?: "${pipeline.name ?: "Pipeline"}: ${version.module?.name ?: node.id}",
                                message = alert["message"]?.toString() ?: "",
                                severity = alert["severity"]?.toString() ?: "WARNING",
                                payload = alert["payload"] as? Map<String, Any?>,
                                dedupKey = "${pipeline.id}:${node.id}:${alert["title"] ?: ""}",
                                cooldownSeconds = (alert["cooldownSeconds"] as? Number)?.toLong(),
                                deliveryKey = runUuid?.let { "run:$it:${node.id}:$index" },
                            )
                        }
                    }
                    alertIds.forEach { alertDispatcher.deliver(it) }
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
        val graph = parseJsonMap(graphJson)
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
            val enrich = (nodeMap["enrich"] as? Map<*, *>)?.takeIf { nodeType == ModuleType.PROCESSING && moduleVersionId == null }
                ?.let { runCatching { EnrichConfig.parse(it) }.getOrElse { e -> throw IllegalStateException("Node $nodeId: ${e.message}") } }
            val saveMetadata = (nodeMap["saveMetadata"] as? Map<*, *>)?.takeIf { nodeType == ModuleType.EXPORT && moduleVersionId == null }
                ?.let { runCatching { SaveMetadataConfig.parse(it) }.getOrElse { e -> throw IllegalStateException("Node $nodeId: ${e.message}") } }
            if (moduleVersionId == null && alertRule == null && enrich == null && saveMetadata == null && nodeType != ModuleType.IMPORT) return@mapNotNull null
            GraphNode(
                id = nodeId,
                type = nodeType,
                moduleVersionId = moduleVersionId,
                connector = connectorsByNodeId[nodeId],
                alertChannel = nodeMap["alertChannel"]?.toString()?.takeIf { it.isNotBlank() },
                alertRule = alertRule,
                enrich = enrich,
                saveMetadata = saveMetadata,
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
