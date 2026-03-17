package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.service.KafkaService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.websocket.Session
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class WebSocketIngestResource {
    
    private val LOG = Logger.getLogger(WebSocketIngestResource::class.java)
    
    @Inject
    lateinit var kafkaService: KafkaService

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @PersistenceContext
    lateinit var em: EntityManager
    
    private val objectMapper = ObjectMapper()
    
    private val sessions = ConcurrentHashMap<String, MutableSet<Session>>()
    
    @Transactional
    fun handleWebSocketMessage(session: Session, pipelineId: String, message: String) {
        val pipeline = Pipeline.findById(UUID.fromString(pipelineId))
        
        if (pipeline == null) {
            LOG.warnf("WebSocket message for unknown pipeline: %s", pipelineId)
            return
        }
        
        if (!pipeline.isActive) {
            LOG.debugf("Pipeline %s is not active, ignoring WebSocket message", pipelineId)
            return
        }
        
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

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.triggerType = TriggerType.DATA
            }
            run.persist()
            em.flush()
            enrichedPayload["_runId"] = run.id.toString()
            
            kafkaService.sendToPipelineSpecificInput(pipelineId, enrichedPayload)
            pipelineConsumer.consume(objectMapper.writeValueAsString(enrichedPayload))
            
            LOG.debugf("WebSocket message queued for pipeline %s", pipelineId)
        } catch (e: Exception) {
            LOG.errorf("Failed to process WebSocket message: %s", e.message)
        }
    }
    
    fun registerSession(pipelineId: String, session: Session) {
        sessions.getOrPut(pipelineId) { mutableSetOf() }.add(session)
        LOG.infof("WebSocket session registered for pipeline %s", pipelineId)
    }
    
    fun unregisterSession(pipelineId: String, session: Session) {
        sessions[pipelineId]?.remove(session)
        LOG.infof("WebSocket session unregistered for pipeline %s", pipelineId)
    }
    
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
