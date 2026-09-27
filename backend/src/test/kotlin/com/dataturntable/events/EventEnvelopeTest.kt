package com.dataturntable.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class EventEnvelopeTest {
    @Test fun `validates required routing and correlation fields`() {
        val tenantId = UUID.randomUUID()
        val valid = EventEnvelope(type = "pipeline.run.requested", tenantId = tenantId, correlationId = "req-1", pipelineId = UUID.randomUUID(), payload = mapOf("x" to 1))
        assertTrue(EventEnvelopeValidator.validate(valid).isEmpty())
        val invalid = valid.copy(correlationId = "", pipelineId = null, pipelineRunId = UUID.randomUUID())
        assertEquals(2, EventEnvelopeValidator.validate(invalid).size)
    }

    @Test fun `rejects unsupported future schema versions`() {
        val event = EventEnvelope(
            type = "pipeline.run.requested",
            schemaVersion = EventEnvelopeValidator.CURRENT_SCHEMA_VERSION + 1,
            tenantId = UUID.randomUUID(),
            correlationId = "req-1",
            payload = emptyMap<String, Any>()
        )

        assertEquals(listOf("schemaVersion is unsupported"), EventEnvelopeValidator.validate(event))
    }

    @Test fun `node identity requires a pipeline context`() {
        val event = EventEnvelope(
            type = "pipeline.node.started",
            tenantId = UUID.randomUUID(),
            correlationId = "request-1",
            pipelineNodeId = "normalize-readings",
            payload = emptyMap<String, Any>()
        )

        assertEquals(listOf("pipelineNodeId requires pipelineId"), EventEnvelopeValidator.validate(event))
    }
}
