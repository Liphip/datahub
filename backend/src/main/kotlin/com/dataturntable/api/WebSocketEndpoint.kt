package com.dataturntable.api

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.websocket.*
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import com.dataturntable.security.SensitiveDataRedactor
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger

@ServerEndpoint("/api/ingest/v1/ws/{pipelineId}")
@ApplicationScoped
/**
 * Defines the class `WebSocketEndpoint`, the contract implemented by this source unit.
 */
class WebSocketEndpoint {
    
    private val LOG = Logger.getLogger(WebSocketEndpoint::class.java)
    
    @Inject
    lateinit var webSocketIngest: WebSocketIngestResource

    @Inject
    lateinit var ingestRateLimiter: com.dataturntable.security.IngestRateLimiter

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    @Inject
    lateinit var workerExecutor: ManagedExecutor

    @ConfigProperty(name = "dataturntable.ingest.websocket.idle-timeout-ms", defaultValue = "300000")
    var idleTimeoutMs: Long = 300000
    
    @OnOpen
    /**
     * Implements `onOpen` for this source unit.
     *
     * JSR-356 callbacks on this endpoint run on the Vert.x IO thread, which Quarkus refuses to
     * start a JTA transaction from. [WebSocketIngestResource.authorizeSession] is transactional,
     * so the actual authorization work is dispatched onto the worker pool.
     */
    fun onOpen(session: Session, @PathParam("pipelineId") pipelineId: String) {
        workerExecutor.execute { authorizeAndConfigure(session, pipelineId) }
    }

    private fun authorizeAndConfigure(session: Session, pipelineId: String) {
        val apiKey = session.requestParameterMap["apiKey"]?.firstOrNull()
        if (!webSocketIngest.authorizeSession(pipelineId, apiKey)) {
            session.close(CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Unauthorized ingestion credential"))
            return
        }
        LOG.infof("Authorized WebSocket connection opened for pipeline: %s", pipelineId)
        // Never retain the raw ingest credential past the handshake.
        configureAuthorizedSession(session, pipelineId, ingestRateLimiter.fingerprint(apiKey!!))
        if (!webSocketIngest.registerSession(pipelineId, session)) {
            session.close(CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "Pipeline session limit reached"))
        }
    }

    @OnMessage
    /**
     * Implements `onMessage` for this source unit.
     *
     * Dispatched onto the worker pool for the same reason as [onOpen]:
     * [WebSocketIngestResource.handleWebSocketMessage] opens a JTA transaction.
     */
    fun onMessage(session: Session, message: String, @PathParam("pipelineId") pipelineId: String) {
        if (session.userProperties["ingestAuthorizedPipeline"] != pipelineId) {
            session.close(CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Unauthorized ingestion session"))
            return
        }
        val fingerprint = session.userProperties["ingestCredentialFingerprint"] as? String
        if (message.length > 64 * 1024 || !withinRateLimit(session) || !ingestRateLimiter.tryAcquireFingerprint(fingerprint)) {
            session.close(CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Message limit exceeded"))
            return
        }
        LOG.debugf("WebSocket message received for pipeline %s: %s", pipelineId, redactor.text(message.take(200)))
        workerExecutor.execute { webSocketIngest.handleWebSocketMessage(session, pipelineId, message) }
    }
    
    @OnClose
    /**
     * Implements `onClose` for this source unit.
     */
    fun onClose(session: Session, closeReason: CloseReason, @PathParam("pipelineId") pipelineId: String) {
        LOG.infof("WebSocket connection closed for pipeline: %s", pipelineId)
        webSocketIngest.unregisterSession(pipelineId, session)
    }
    
    @OnError
    /**
     * Implements `onError` for this source unit.
     */
    fun onError(session: Session, error: Throwable, @PathParam("pipelineId") pipelineId: String) {
        LOG.errorf(error, "WebSocket error for pipeline: %s", pipelineId)
        webSocketIngest.unregisterSession(pipelineId, session)
    }

    internal fun configureAuthorizedSession(session: Session, pipelineId: String, credentialFingerprint: String) {
        session.maxTextMessageBufferSize = 64 * 1024
        // Session.setMaxIdleTimeout is a no-op on this stack's JSR-356 implementation (confirmed by
        // decompiling quarkus-http-websocket-core's UndertowSession — the method body is an empty
        // `return`). It's still set here per the JSR-356 contract in case a future Quarkus version
        // implements it, but the actual enforcement is WebSocketIngestResource.reapIdleSessions,
        // a scheduled reaper keyed off the same idleTimeoutMs value.
        session.maxIdleTimeout = idleTimeoutMs
        session.userProperties["ingestWindowStart"] = System.currentTimeMillis()
        session.userProperties["ingestMessageCount"] = 0
        session.userProperties["ingestAuthorizedPipeline"] = pipelineId
        session.userProperties["ingestCredentialFingerprint"] = credentialFingerprint
    }

    internal fun withinRateLimit(session: Session): Boolean {
        synchronized(session) {
            val now = System.currentTimeMillis()
            val windowStart = session.userProperties["ingestWindowStart"] as? Long ?: now
            val count = session.userProperties["ingestMessageCount"] as? Int ?: 0
            if (now - windowStart >= 60_000) {
                session.userProperties["ingestWindowStart"] = now
                session.userProperties["ingestMessageCount"] = 1
                return true
            }
            if (count >= 600) return false
            session.userProperties["ingestMessageCount"] = count + 1
            return true
        }
    }
}
