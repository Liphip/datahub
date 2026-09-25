package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.engine.PipelineQueueUnavailableException
import com.dataturntable.engine.PipelineRunQueue
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.websocket.Session
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

    @PersistenceContext
    lateinit var em: EntityManager

    private val objectMapper = ObjectMapper()

    private val sessions = ConcurrentHashMap<String, MutableSet<Session>>()

    /**
     * Queues one message and answers the sender with `{"status":"accepted","runId":...}` or,
     * when the record is not stored, `{"status":"error","retry":true}` so it can resend.
     */
    fun handleWebSocketMessage(session: Session, pipelineId: String, message: String) {
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
            ?: return reply(session, mapOf("status" to "error", "retry" to false, "error" to "Invalid pipelineId"))
        val pipeline = em.find(Pipeline::class.java, pipelineUuid)
        if (pipeline == null || !pipeline.isActive) {
            return reply(session, mapOf("status" to "error", "retry" to false, "error" to "Pipeline not found or not active"))
        }

        @Suppress("UNCHECKED_CAST")
        val payload = runCatching { objectMapper.readValue(message, Map::class.java) as Map<String, Any?> }
            .getOrElse { mapOf("raw" to message) }
        val data = payload.toMutableMap()
        data["_ingestedAt"] = java.time.Instant.now().toString()
        data["_source"] = "websocket"

        try {
            val runId = runQueue.enqueue(pipelineUuid, TriggerType.DATA, data)
            reply(session, mapOf("status" to "accepted", "runId" to runId.toString()))
        } catch (e: PipelineQueueUnavailableException) {
            reply(session, mapOf("status" to "error", "retry" to true, "error" to e.message))
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
    fun registerSession(pipelineId: String, session: Session) {
        sessions.computeIfAbsent(pipelineId) { ConcurrentHashMap.newKeySet() }.add(session)
        LOG.infof("WebSocket session registered for pipeline %s", pipelineId)
    }
    
    /**
     * Implements `unregisterSession` for this source unit.
     */
    fun unregisterSession(pipelineId: String, session: Session) {
        sessions[pipelineId]?.remove(session)
        LOG.infof("WebSocket session unregistered for pipeline %s", pipelineId)
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
