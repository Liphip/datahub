package com.dataturntable.api

import com.dataturntable.auth.ApiKey
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.websocket.ClientEndpoint
import jakarta.websocket.CloseReason
import jakarta.websocket.ContainerProvider
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives [WebSocketEndpoint] through a real JSR-356 client/server round trip instead of
 * unit-testing its helper methods against a mocked [Session]. Covers the behavior the status
 * tracker flagged as outstanding: actual authorize/reject close codes reaching a real client,
 * a real accepted-message acknowledgement, the session limit rejecting a real connection, and
 * [WebSocketIngestResource.unregisterSession] actually running when a real socket closes.
 */
@QuarkusTest
class WebSocketIngestE2ETest {
    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var webSocketIngest: WebSocketIngestResource

    @TestHTTPResource("/api/ingest/v1/ws")
    lateinit var wsBase: URI

    private fun awaitTrue(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Transactional
    fun createActivePipelineWithKey(): Pair<String, String> {
        val tenant = Tenant().apply { name = "ws-e2e-${UUID.randomUUID()}" }
        em.persist(tenant)
        val pipeline = Pipeline().apply {
            this.tenant = tenant
            name = "ws-e2e-pipeline-${UUID.randomUUID()}"
            isActive = true
        }
        em.persist(pipeline)
        em.flush()
        val rawKey = "ws-e2e-secret-${UUID.randomUUID()}"
        val apiKey = ApiKey().apply {
            this.tenant = tenant
            name = "ws-e2e-key-${UUID.randomUUID()}"
            keyHash = sha256(rawKey)
            abilities = "[\"ingest:pipeline:${pipeline.id}\"]"
        }
        em.persist(apiKey)
        em.flush()
        return pipeline.id.toString() to rawKey
    }

    @ClientEndpoint
    class RecordingClient {
        val messages = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var closeReason: CloseReason? = null

        @OnOpen
        fun onOpen(session: Session) { opened.countDown() }

        @OnMessage
        fun onMessage(message: String) { messages.add(message) }

        @OnClose
        fun onClose(reason: CloseReason) {
            closeReason = reason
            closed.countDown()
        }
    }

    @Test
    fun `a real client with an invalid credential is rejected with VIOLATED_POLICY`() {
        val (pipelineId, _) = createActivePipelineWithKey()
        val client = RecordingClient()
        val container = ContainerProvider.getWebSocketContainer()
        val session = container.connectToServer(client, URI.create("$wsBase/$pipelineId?apiKey=wrong-secret"))
        try {
            assertTrue(client.closed.await(10, TimeUnit.SECONDS))
            assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY, client.closeReason?.closeCode)
        } finally {
            if (session.isOpen) session.close()
        }
    }

    @Test
    fun `a real client with a valid credential connects, sends, and is acknowledged`() {
        val (pipelineId, rawKey) = createActivePipelineWithKey()
        val client = RecordingClient()
        val container = ContainerProvider.getWebSocketContainer()
        val session = container.connectToServer(client, URI.create("$wsBase/$pipelineId?apiKey=$rawKey"))
        try {
            assertTrue(client.opened.await(10, TimeUnit.SECONDS))
            // Authorization/registration now runs on a worker thread off the handshake, so wait for it.
            assertTrue(awaitTrue(10_000) { webSocketIngest.sessionCount(pipelineId) == 1 })

            session.basicRemote.sendText("""{"value": 42}""")

            val deadline = System.currentTimeMillis() + 10_000
            while (client.messages.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(client.messages.isNotEmpty(), "expected an ingest.accepted acknowledgement")
            assertTrue(client.messages[0].contains("\"type\":\"ingest.accepted\""))
        } finally {
            session.close()
        }

        assertTrue(client.closed.await(10, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (webSocketIngest.sessionCount(pipelineId) != 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, webSocketIngest.sessionCount(pipelineId), "server must unregister the session on real close")
    }

    @Test
    fun `the 101st real connection to one pipeline is rejected with TRY_AGAIN_LATER`() {
        val (pipelineId, rawKey) = createActivePipelineWithKey()
        val container = ContainerProvider.getWebSocketContainer()
        val sessions = mutableListOf<Session>()
        try {
            repeat(100) {
                val client = RecordingClient()
                val session = container.connectToServer(client, URI.create("$wsBase/$pipelineId?apiKey=$rawKey"))
                assertTrue(client.opened.await(10, TimeUnit.SECONDS))
                sessions.add(session)
            }
            assertTrue(awaitTrue(10_000) { webSocketIngest.sessionCount(pipelineId) == 100 })

            val overflowClient = RecordingClient()
            val overflowSession = container.connectToServer(overflowClient, URI.create("$wsBase/$pipelineId?apiKey=$rawKey"))
            try {
                assertTrue(overflowClient.closed.await(10, TimeUnit.SECONDS))
                assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, overflowClient.closeReason?.closeCode)
            } finally {
                if (overflowSession.isOpen) overflowSession.close()
            }
        } finally {
            sessions.forEach { if (it.isOpen) runCatching { it.close() } }
        }
    }
}
