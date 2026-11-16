package com.dataturntable.api

import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class WebSocketIngestResourceTest {
    @Test
    fun `concurrent registration never exceeds a pipeline session limit`() {
        val resource = WebSocketIngestResource()
        val pipelineId = "pipeline-1"
        val executor = Executors.newFixedThreadPool(16)
        try {
            val results = executor.invokeAll((1..200).map { index ->
                Callable { resource.registerSession(pipelineId, session("session-$index")) }
            }).map { it.get() }

            assertEquals(100, results.count { it })
            assertEquals(100, resource.sessionCount(pipelineId))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the session limit is configurable and every refused session is counted`() {
        // A plain unit test has no Quarkus registry bound to Micrometer's global one, and the empty
        // composite hands out no-op counters; attach a simple registry for the duration of the test.
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        io.micrometer.core.instrument.Metrics.addRegistry(registry)
        val resource = WebSocketIngestResource().apply { maxSessionsPerPipeline = 2 }
        val before = IngestRejections.total("websocket_session_limit")
        assertTrue(resource.registerSession("pipeline-3", session("a")))
        assertTrue(resource.registerSession("pipeline-3", session("b")))
        assertFalse(resource.registerSession("pipeline-3", session("c")))
        assertEquals(2, resource.sessionCount("pipeline-3"))
        assertEquals(before + 1.0, IngestRejections.total("websocket_session_limit"))
        io.micrometer.core.instrument.Metrics.removeRegistry(registry)
    }

    @Test
    fun `a distinct session can be registered until the limit is reached`() {
        val resource = WebSocketIngestResource()
        assertTrue(resource.registerSession("pipeline-2", session("one")))
        assertEquals(1, resource.sessionCount("pipeline-2"))
    }

    @Test
    fun `authorized session has bounded message and idle limits without retaining a raw credential`() {
        val endpoint = WebSocketEndpoint()
        val state = SessionState()
        endpoint.configureAuthorizedSession(state.session, "pipeline-3", "credential-fingerprint")

        assertEquals(64 * 1024, state.maxTextMessageBufferSize)
        assertEquals(5 * 60 * 1000L, state.maxIdleTimeout)
        assertEquals("pipeline-3", state.properties["ingestAuthorizedPipeline"])
        assertEquals("credential-fingerprint", state.properties["ingestCredentialFingerprint"])
        assertFalse(state.properties.values.any { it == "raw-api-key" })
    }

    @Test
    fun `per-session rate window rejects the 601st message`() {
        val endpoint = WebSocketEndpoint()
        val state = SessionState()
        endpoint.configureAuthorizedSession(state.session, "pipeline-4", "fingerprint")

        repeat(600) { assertTrue(endpoint.withinRateLimit(state.session)) }
        assertFalse(endpoint.withinRateLimit(state.session))
    }

    private class SessionState {
        val properties = mutableMapOf<String, Any>()
        var maxTextMessageBufferSize: Int? = null
        var maxIdleTimeout: Long? = null
        val session: Session = Proxy.newProxyInstance(
            Session::class.java.classLoader,
            arrayOf(Session::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getUserProperties" -> properties
                "setMaxTextMessageBufferSize" -> {
                    maxTextMessageBufferSize = args?.firstOrNull() as? Int
                    null
                }
                "setMaxIdleTimeout" -> {
                    maxIdleTimeout = args?.firstOrNull() as? Long
                    null
                }
                else -> null
            }
        } as Session
    }

    private fun session(id: String): Session = Proxy.newProxyInstance(
        Session::class.java.classLoader,
        arrayOf(Session::class.java)
    ) { _, method, args ->
        when (method.name) {
            "hashCode" -> id.hashCode()
            "equals" -> args?.firstOrNull() === id
            "toString" -> id
            else -> null
        }
    } as Session
}
