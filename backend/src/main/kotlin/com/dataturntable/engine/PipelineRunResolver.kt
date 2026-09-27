package com.dataturntable.engine

import com.dataturntable.events.ConsumerDeadLetterService
import com.dataturntable.events.EventInboxService
import com.dataturntable.pipeline.Pipeline
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.util.UUID

/** Signals an unrecoverable wire/target problem to the Kafka connector. */
class PoisonPipelineEvent(message: String) : IllegalArgumentException(message)

/** Outcome of resolving a raw Kafka message to a concrete pipeline/run ready to execute. */
data class ResolvedRun(
    val pipeline: Pipeline?,
    val runUuid: UUID?,
    val runId: String?,
    val dataPayload: Map<String, Any>?,
    /** Non-null when resolution already produced the final result (skip/cancelled) and execution must not run. */
    val shortCircuit: PipelineEvent?
)

/**
 * Parses, validates, deduplicates, and marks a message's target run RUNNING — the part of
 * [PipelineConsumer.consume] that must be retried as a whole on transient DB failure. Kept as a
 * separate CDI bean (not a private method on [PipelineConsumer]) so its `@Transactional` boundary
 * is actually honored: ArC/CDI interceptors do not apply to self-invocation within the same bean,
 * so [PipelineConsumer] must call this through injection, not `this`.
 */
@ApplicationScoped
class PipelineRunResolver {

    private val LOG = Logger.getLogger(PipelineRunResolver::class.java)
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    @Inject
    lateinit var eventInbox: EventInboxService

    @Inject
    lateinit var consumerDeadLetters: ConsumerDeadLetterService

    @Inject
    lateinit var logWriter: PipelineLogWriter

    private fun poison(payload: String, reason: String): Nothing {
        consumerDeadLetters.record("pipeline-input", payload, reason)
        throw PoisonPipelineEvent(reason)
    }

    private fun unwrapEventPayload(message: Map<String, Any?>): Map<String, Any?> {
        val payload = message["payload"] as? Map<*, *> ?: return message
        return payload.entries.associate { it.key.toString() to it.value }
    }

    private fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }

    @Transactional
    fun resolveAndMarkRunning(message: String): ResolvedRun {
        val rawMap = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(message, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) {
            LOG.errorf("Failed to parse pipeline message: %s", e.message)
            poison(message, "Invalid JSON pipeline event: ${e.message}")
        }
        val envelope = rawMap?.let(PipelineEventEnvelopeValidator::validate)?.getOrElse { error ->
            LOG.warnf("Rejected invalid pipeline event envelope: %s", error.message)
            poison(message, "Invalid pipeline event envelope: ${error.message}")
        }
        val envelopeEventId = envelope?.eventId
        val pipelineMsg: PipelineTriggerMessage?
        var dataPayload: Map<String, Any>? = null
        var pipelineId: String? = null

        try {
            val normalized = rawMap?.let(::unwrapEventPayload)
            pipelineMsg = if (normalized?.containsKey("runId") == true && normalized.containsKey("pipelineId")) {
                objectMapper.convertValue(normalized, PipelineTriggerMessage::class.java)
            } else null

            if (pipelineMsg != null) {
                pipelineId = pipelineMsg.pipelineId
                if (pipelineMsg.data is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    dataPayload = pipelineMsg.data as? Map<String, Any>
                }
            } else {
                try {
                    @Suppress("UNCHECKED_CAST")
                    dataPayload = normalized as? Map<String, Any>
                    pipelineId = (dataPayload?.get("_pipelineId") as? String)
                        ?: (dataPayload?.get("pipelineId") as? String)
                } catch (e: Exception) {
                    LOG.errorf("Failed to parse pipeline message: %s", e.message)
                    throw PoisonPipelineEvent("Invalid pipeline payload: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (e is PoisonPipelineEvent) throw e
            LOG.errorf("Failed to parse pipeline message: %s", e.message)
            throw PoisonPipelineEvent("Invalid pipeline payload: ${e.message}")
        }

        if (pipelineId == null) {
            LOG.error("No pipeline ID found in message")
            throw PoisonPipelineEvent("No pipeline ID in message")
        }

        val pipelineUuid = try {
            UUID.fromString(pipelineId)
        } catch (_: Exception) {
            throw PoisonPipelineEvent("Invalid pipeline ID")
        }

        val pipeline = Pipeline.findById(pipelineUuid)
        if (pipeline == null) {
            LOG.errorf("Pipeline not found: %s", pipelineId)
            throw PoisonPipelineEvent("Pipeline not found: $pipelineId")
        }

        if (envelope?.pipelineId != null && envelope.pipelineId != pipelineUuid) {
            throw PoisonPipelineEvent("Envelope pipeline does not match payload target")
        }
        if (envelope?.tenantId != null && envelope.tenantId != pipeline.tenant?.id) {
            throw PoisonPipelineEvent("Envelope tenant does not own target pipeline")
        }

        // Only make an event durable for deduplication after both its payload and
        // execution target are valid. Claiming earlier turns malformed messages or
        // a transient missing-pipeline deployment race into permanently skipped work.
        if (envelopeEventId != null && !eventInbox.claim(envelopeEventId)) {
            return ResolvedRun(null, null, null, null, PipelineEvent("skipped", pipelineId, "deduplication", true, null))
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
            return ResolvedRun(null, null, null, null, PipelineEvent(
                runId = runId,
                pipelineId = pipelineId,
                stage = "cancelled",
                success = false,
                error = "Run cancelled"
            ))
        }

        // Mark RUNNING — committed immediately so frontend sees it
        if (runUuid != null) {
            logWriter.markRunning(runUuid)
        }

        return ResolvedRun(pipeline, runUuid, runId, dataPayload, null)
    }
}
