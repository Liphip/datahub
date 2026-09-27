package com.dataturntable.events

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

data class EventEnvelope<T>(
    val eventId: UUID = UUID.randomUUID(),
    val type: String,
    val schemaVersion: Int = 1,
    val tenantId: UUID,
    val occurredAt: Instant = Instant.now(),
    val correlationId: String,
    val causationId: String? = null,
    val pipelineId: UUID? = null,
    val pipelineRunId: UUID? = null,
    val pipelineNodeId: String? = null,
    val executionId: UUID? = null,
    val payload: T
)

object EventEnvelopeValidator {
    const val CURRENT_SCHEMA_VERSION = 1

    fun validate(event: EventEnvelope<*>): List<String> = buildList {
        if (event.type.isBlank()) add("type is required")
        if (event.schemaVersion !in 1..CURRENT_SCHEMA_VERSION) add("schemaVersion is unsupported")
        if (event.correlationId.isBlank()) add("correlationId is required")
        if (event.pipelineRunId != null && event.pipelineId == null) add("pipelineRunId requires pipelineId")
        if (!event.pipelineNodeId.isNullOrBlank() && event.pipelineId == null) add("pipelineNodeId requires pipelineId")
    }
    fun requireValid(event: EventEnvelope<*>) {
        val errors = validate(event)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}
