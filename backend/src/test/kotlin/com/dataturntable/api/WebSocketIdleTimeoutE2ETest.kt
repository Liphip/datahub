package com.dataturntable.api

import com.dataturntable.auth.ApiKey
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.websocket.ClientEndpoint
import jakarta.websocket.ContainerProvider
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The production idle timeout defaults to 5 minutes, which is not practical to wait out in a
 * test. This profile overrides both the idle timeout and the reaper's scan interval down to
 * small values so the test completes quickly.
 *
 * Root-caused this session (previously an open mystery — see git history for prior write-ups
 * theorizing about `Session.setMaxIdleTimeout`/Vert.x `webSocketClosingTimeout`): decompiling
 * `quarkus-http-websocket-core:5.4.0`'s `UndertowSession.setMaxIdleTimeout(long)` shows the method
 * body is a single, unconditional `return` — a complete no-op. There is no other server-side idle
 * enforcement on this stack, so idle ingest sessions never closed on their own. Confirmed directly:
 * a test client left idle for 150+ seconds still reported `session.isOpen() == true` and never
 * received `@OnClose`/`@OnError`, while the "server closes it eventually" behavior earlier test
 * runs appeared to observe was actually this test's own `finally` block closing the client
 * session after its assertion timed out — not any real server-side mechanism.
 *
 * Fixed properly in `WebSocketIngestResource`: session activity is tracked in a
 * `lastActivity` map, and a `@Scheduled` reaper (`reapIdleSessions`) closes sessions that have
 * gone quiet past `idle-timeout-ms`. This test verifies that reaper directly against the
 * server's own session-tracking state (`WebSocketIngestResource.sessionCount`), not the WebSocket
 * client library's close-callback delivery, since that was independently found to be unreliable
 * in this environment (client `isOpen()`/`@OnClose`/`@OnError` never fired even once the
 * connection was provably gone server-side).
 */
class ShortWebSocketIdleTimeoutProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "test"
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "dataturntable.ingest.websocket.idle-timeout-ms" to "1500",
        "dataturntable.ingest.websocket.idle-reap-interval" to "500ms"
    )
}

@QuarkusTest
@TestProfile(ShortWebSocketIdleTimeoutProfile::class)
class WebSocketIdleTimeoutE2ETest {
    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var webSocketIngest: WebSocketIngestResource

    @TestHTTPResource("/api/ingest/v1/ws")
    lateinit var wsBase: URI

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Transactional
    fun createActivePipelineWithKey(): Pair<String, String> {
        val tenant = Tenant().apply { name = "ws-idle-${UUID.randomUUID()}" }
        em.persist(tenant)
        val pipeline = Pipeline().apply {
            this.tenant = tenant
            name = "ws-idle-pipeline-${UUID.randomUUID()}"
            isActive = true
        }
        em.persist(pipeline)
        em.flush()
        val rawKey = "ws-idle-secret-${UUID.randomUUID()}"
        val apiKey = ApiKey().apply {
            this.tenant = tenant
            name = "ws-idle-key-${UUID.randomUUID()}"
            keyHash = sha256(rawKey)
            abilities = "[\"ingest:pipeline:${pipeline.id}\"]"
        }
        em.persist(apiKey)
        em.flush()
        return pipeline.id.toString() to rawKey
    }

    @ClientEndpoint
    class RecordingClient {
        val opened = CountDownLatch(1)

        @OnOpen
        fun onOpen(session: Session) { opened.countDown() }
    }

    @Test
    fun `an authorized session with no activity is eventually closed by the server`() {
        val (pipelineId, rawKey) = createActivePipelineWithKey()
        val client = RecordingClient()
        val container = ContainerProvider.getWebSocketContainer()
        val session = container.connectToServer(client, URI.create("$wsBase/$pipelineId?apiKey=$rawKey"))
        try {
            assertTrue(client.opened.await(10, TimeUnit.SECONDS))
            assertTrue(waitUntilSessionCountIs(pipelineId, 0, 15_000), "reaper must eventually close an idle session")
        } finally {
            runCatching { session.close() }
        }
    }

    private fun waitUntilSessionCountIs(pipelineId: String, expected: Int, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (webSocketIngest.sessionCount(pipelineId) == expected) return true
            Thread.sleep(100)
        }
        return webSocketIngest.sessionCount(pipelineId) == expected
    }
}
