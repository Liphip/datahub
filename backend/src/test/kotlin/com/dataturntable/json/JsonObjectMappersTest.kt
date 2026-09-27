package com.dataturntable.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class JsonObjectMappersTest {
    @Test
    fun `serializes Instant values embedded in untyped payloads`() {
        val serialized = JsonObjectMappers.create().writeValueAsString(
            mapOf("occurredAt" to Instant.parse("2026-08-18T08:30:00Z"))
        )

        assertEquals("2026-08-18T08:30:00Z", JsonObjectMappers.create()
            .readTree(serialized).get("occurredAt").asText())
    }
}
