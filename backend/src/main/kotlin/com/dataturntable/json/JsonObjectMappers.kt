package com.dataturntable.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

/**
 * Creates the mapper used by application-owned JSON persistence and message paths.
 *
 * A bare Jackson [ObjectMapper] cannot serialize Java time values such as [java.time.Instant].
 * Those values legitimately occur inside untyped event, audit, configuration, and alert payloads,
 * so every local mapper must carry the Java Time module rather than relying on a caller to avoid it.
 */
object JsonObjectMappers {
    @JvmStatic
    fun create(): ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
