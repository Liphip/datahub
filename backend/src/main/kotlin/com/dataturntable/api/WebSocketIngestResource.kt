package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.engine.PipelineQueueUnavailableException
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.security.IngestAuthorizationService
import com.dataturntable.security.SensitiveDataRedactor
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.websocket.CloseReason
import jakarta.websocket.Session
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
/**
 * Defines the class `WebSocketIngestResource`, the contract implemented by this source unit.
 */
class WebSocketIngestResource {
    
    private val LOG = Logger.getLogger(WebSocketIngestResource::class.java)
    
    @Inject
    lateinit var runQueue: PipelineRunQueue

    @Inject
    lateinit var directory: PipelineDirectory

    @Inject
    lateinit var ingestAuthorization: IngestAuthorizationService

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    @ConfigProperty(name = "dataturntable.ingest.websocket.idle-timeout-ms", defaultValue = "300000")
    var idleTimeoutMs: Long = 300000

    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    private val sessions = ConcurrentHashMap<String, MutableSet<Session>>()

    // jakarta.websocket.Session.setMaxIdleTimeout is a no-op on Quarkus's Vert.x-native JSR-356
    // implementation, so idle ingest sessions would otherwise never close on their own. This tracks
    // last-activity time per session and a scheduled reaper (below) closes anything that's gone
    // quiet past the configured timeout.
    private val lastActivity = ConcurrentHashMap<Session, Long>()

    private fun touch(session: Session) {
        lastActivity[session] = System.currentTimeMillis()
    }

    /** Checked once when the socket opens; uses the cached pipeline directory and API keys (no database). */
    fun authorizeSession(pipelineId: String, apiKey: String?): Boolean {
        val id = runCatching { UUID.fromString(pipelineId) }.getOrNull() ?: return false
        val pipeline = directory.get(id) ?: return false
        return pipeline.active && ingestAuthorization.authorize(apiKey, pipeline.tenantId, "pipeline", id)
    }
    /**
     * Queues one message and answers the sender with `{"type":"ingest.accepted","status":"accepted","runId":...}`
     * or, when the record is not stored, `{"type":"ingest.rejected","status":"error","retry":true}` so it can resend.
     * A top-level `_idempotencyKey` (or the pipeline's `deduplicateBy` fields) makes resends
     * of the same message map to the same run.
     */
    fun handleWebSocketMessage(session: Session, pipelineId: String, message: String) {
        touch(session)
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
            ?: return reply(session, mapOf("type" to "ingest.rejected", "status" to "error", "retry" to false, "error" to "Invalid pipelineId"))
        val pipeline = directory.get(pipelineUuid)
        if (pipeline == null || !pipeline.active) {
            return reply(session, mapOf("type" to "ingest.rejected", "status" to "error", "retry" to false, "error" to "Pipeline not found or not active"))
        }

        @Suppress("UNCHECKED_CAST")
        val payload = runCatching { objectMapper.readValue(message, Map::class.java) as Map<String, Any?> }
            .getOrElse { mapOf("raw" to message) }
        val data = payload.toMutableMap()
        val key = data.remove("_idempotencyKey")?.toString()?.takeIf { it.isNotBlank() }
            ?: PipelineDirectory.recordKey(payload, pipeline.deduplicateBy)
        data["_ingestedAt"] = java.time.Instant.now().toString()
        data["_source"] = "websocket"

        try {
            val runId = runQueue.enqueue(pipelineUuid, TriggerType.DATA, data, idempotencyKey = key)
            reply(session, mapOf("type" to "ingest.accepted", "status" to "accepted", "runId" to runId.toString()))
        } catch (e: PipelineQueueUnavailableException) {
            reply(session, mapOf("type" to "ingest.rejected", "status" to "error", "retry" to true, "error" to e.message))
        }
    }

    private fun reply(session: Session, body: Map<String, Any?>) {
        try {
            session.basicRemote.sendText(objectMapper.writeValueAsString(body))
        } catch (e: Exception) {
            LOG.debugf("Could not answer WebSocket client: %s", e.message)
        }
    }

    /**
     * Creates or persists the requested state.
     */
    fun registerSession(pipelineId: String, session: Session): Boolean {
        var registered = false
        sessions.compute(pipelineId) { _, existing ->
            val pipelineSessions = existing ?: ConcurrentHashMap.newKeySet()
            if (pipelineSessions.size < 100 && pipelineSessions.add(session)) registered = true
            pipelineSessions
        }
        if (registered) {
            touch(session)
            LOG.infof("WebSocket session registered for pipeline %s", pipelineId)
        }
        return registered
    }

    internal fun sessionCount(pipelineId: String): Int = sessions[pipelineId]?.size ?: 0
    
    /**
     * Implements `unregisterSession` for this source unit.
     */
    fun unregisterSession(pipelineId: String, session: Session) {
        sessions.computeIfPresent(pipelineId) { _, pipelineSessions ->
            pipelineSessions.remove(session)
            pipelineSessions.takeUnless { it.isEmpty() }
        }
        lastActivity.remove(session)
        LOG.infof("WebSocket session unregistered for pipeline %s", pipelineId)
    }

    /**
     * Closes ingest WebSocket sessions that have received no message since before the configured
     * idle timeout. Runs frequently relative to the timeout itself so the actual enforced window
     * stays close to what's configured, since `Session.setMaxIdleTimeout` does not do this for us
     * on this stack (see the `lastActivity` field comment above).
     */
    @Scheduled(every = "{dataturntable.ingest.websocket.idle-reap-interval}")
    fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        sessions.values.forEach { pipelineSessions ->
            pipelineSessions.forEach { session ->
                val last = lastActivity[session] ?: return@forEach
                if (session.isOpen && now - last >= idleTimeoutMs) {
                    runCatching {
                        session.close(CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Idle timeout"))
                    }.onFailure { LOG.debugf("Failed to close idle WebSocket session: %s", it.message) }
                }
            }
        }
    }
    
    /**
     * Implements `broadcastToPipeline` for this source unit.
     */
    fun broadcastToPipeline(pipelineId: String, message: Map<String, Any>) {
        sessions[pipelineId]?.forEach { session ->
            try {
                session.getBasicRemote().sendText(objectMapper.writeValueAsString(message))
            } catch (e: Exception) {
                LOG.warnf("Failed to send WebSocket message: %s", e.message)
            }
        }
    }
}
