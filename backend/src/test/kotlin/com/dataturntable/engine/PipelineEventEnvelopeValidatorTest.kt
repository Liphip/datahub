package com.dataturntable.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PipelineEventEnvelopeValidatorTest {
    private val tenantId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()

    private fun validEnvelope() = mapOf<String, Any?>(
        "eventId" to UUID.randomUUID().toString(),
        "type" to "pipeline.run.requested",
        "schemaVersion" to 1,
        "tenantId" to tenantId.toString(),
        "correlationId" to "run-1",
        "pipelineId" to pipelineId.toString(),
        "payload" to mapOf("pipelineId" to pipelineId.toString())
    )

    @Test
    fun `accepts a supported complete envelope and leaves legacy messages unwrapped`() {
        val validated = PipelineEventEnvelopeValidator.validate(validEnvelope()).getOrThrow()
        assertEquals(tenantId, validated.tenantId)
        assertEquals(pipelineId, validated.pipelineId)
        assertEquals(null, PipelineEventEnvelopeValidator.validate(mapOf("pipelineId" to pipelineId.toString())).getOrThrow().eventId)
    }

    @Test
    fun `rejects malformed and unsupported envelopes before their payload is used`() {
        listOf(
            validEnvelope() - "tenantId",
            validEnvelope() + ("schemaVersion" to 2),
            validEnvelope() + ("pipelineId" to "not-a-uuid"),
            validEnvelope() - "payload"
        ).forEach { message ->
            assertTrue(PipelineEventEnvelopeValidator.validate(message).isFailure)
        }
    }
}
