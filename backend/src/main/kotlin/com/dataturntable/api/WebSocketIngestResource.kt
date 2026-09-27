package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineVersionService
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.service.KafkaService
import com.dataturntable.security.IngestAuthorizationService
import com.dataturntable.security.SensitiveDataRedactor
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
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
    lateinit var kafkaService: KafkaService

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @Inject
    lateinit var ingestAuthorization: IngestAuthorizationService

    @Inject
    lateinit var pipelineVersions: PipelineVersionService

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    @PersistenceContext
    lateinit var em: EntityManager
    
    @ConfigProperty(name = "dataturntable.ingest.websocket.idle-timeout-ms", defaultValue = "300000")
    var idleTimeoutMs: Long = 300000

    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    private val sessions = ConcurrentHashMap<String, MutableSet<Session>>()

    // jakarta.websocket.Session.setMaxIdleTimeout is a no-op on Quarkus's Vert.x-native JSR-356
    // implementation (quarkus-http-websocket-core 5.4.0's UndertowSession.setMaxIdleTimeout body is
    // an empty `return` — confirmed by decompilation, not assumed). There is no other built-in idle
    // enforcement, so idle ingest sessions would otherwise never close on their own. This tracks
    // last-activity time per session and a scheduled reaper (below) closes anything that's gone
    // quiet past the configured timeout.
    private val lastActivity = ConcurrentHashMap<Session, Long>()

    private fun touch(session: Session) {
        lastActivity[session] = System.currentTimeMillis()
    }

    @Transactional
    fun authorizeSession(pipelineId: String, apiKey: String?): Boolean {
        val id = runCatching { UUID.fromString(pipelineId) }.getOrNull() ?: return false
        val pipeline = em.find(Pipeline::class.java, id) ?: return false
        return pipeline.isActive && ingestAuthorization.authorize(apiKey, pipeline.tenant, "pipeline", id)
    }
    
    @Transactional
    /**
     * Processes the named request, event, or operation.
     */
    fun handleWebSocketMessage(session: Session, pipelineId: String, message: String) {
        touch(session)
        val pipelineUuid = try {
            UUID.fromString(pipelineId)
        } catch (_: Exception) {
            LOG.warnf("Invalid pipelineId in WebSocket message: %s", pipelineId)
            return
        }
        val pipeline = em.find(Pipeline::class.java, pipelineUuid)
        
        if (pipeline == null) {
            LOG.warnf("WebSocket message for unknown pipeline: %s", pipelineId)
            return
        }
        
        if (!pipeline.isActive) {
            LOG.debugf("Pipeline %s is not active, ignoring WebSocket message", pipelineId)
            return
        }
        
        var run: PipelineRun? = null
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = try {
                objectMapper.readValue(message, Map::class.java) as? Map<String, Any>
            } catch (e: Exception) {
                mapOf("raw" to message)
            }
            
            val enrichedPayload = (payload ?: mutableMapOf()).toMutableMap()
            enrichedPayload["_pipelineId"] = pipelineId
            enrichedPayload["_ingestedAt"] = java.time.Instant.now().toString()
            enrichedPayload["_source"] = "websocket"

            run = PipelineRun().apply {
                this.pipeline = pipeline
                this.pipelineVersion = pipelineVersions.latestFor(pipeline.id)
                this.triggerType = TriggerType.DATA
            }
            run.persist()
            em.flush()
            enrichedPayload["_runId"] = run.id.toString()
            
            kafkaService.sendToPipelineSpecificInput(pipelineId, enrichedPayload)
            if (!kafkaService.isKafkaAvailable()) {
                pipelineConsumer.consume(objectMapper.writeValueAsString(enrichedPayload))
            }
            sendOutcome(session, "accepted", run.id?.toString())
            LOG.debugf("WebSocket message queued for pipeline %s", pipelineId)
        } catch (e: Exception) {
            val safeMessage = redactor.text(e.message) ?: "WebSocket ingestion failed"
            run?.markFailed(safeMessage)
            run?.persist()
            sendOutcome(session, "rejected", run?.id?.toString())
            LOG.errorf("Failed to process WebSocket message: %s", safeMessage)
        }
    }

    /** Gives WebSocket producers a deterministic acknowledgement instead of an ambiguous silent drop. */
    private fun sendOutcome(session: Session, outcome: String, runId: String?) {
        try {
            session.basicRemote.sendText(objectMapper.writeValueAsString(mapOf("type" to "ingest.$outcome", "runId" to runId)))
        } catch (e: Exception) {
            LOG.debugf("Unable to send WebSocket ingest acknowledgement: %s", redactor.text(e.message))
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
