package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.service.KafkaService
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class IngestResource {
    
    @Inject
    lateinit var kafkaService: KafkaService

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @PersistenceContext
    lateinit var em: EntityManager
    
    private val objectMapper = ObjectMapper()
    
    @POST
    @Path("/ingest/{pipelineId}")
    @Transactional
    fun ingest(
        @PathParam("pipelineId") pipelineId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        @HeaderParam("X-Tenant-Id") tenantId: String?,
        payload: Map<String, Any>
    ): Response {
        val pipeline = Pipeline.findById(UUID.fromString(pipelineId))
        
        if (pipeline == null) {
            return Response.status(404)
                .entity(mapOf("error" to "Pipeline not found"))
                .build()
        }
        
        if (!pipeline.isActive) {
            return Response.status(400)
                .entity(mapOf("error" to "Pipeline is not active"))
                .build()
        }
        
        val enrichedPayload = payload.toMutableMap()
        enrichedPayload["_pipelineId"] = pipelineId
        enrichedPayload["_ingestedAt"] = java.time.Instant.now().toString()
        tenantId?.let { enrichedPayload["_tenantId"] = it }

        val run = PipelineRun().apply {
            this.pipeline = pipeline
            this.triggerType = TriggerType.DATA
        }
        run.persist()
        em.flush()
        enrichedPayload["_runId"] = run.id.toString()
        
        try {
            kafkaService.sendToPipelineSpecificInput(pipeline.id.toString(), enrichedPayload)
            if (!kafkaService.isKafkaAvailable()) {
                pipelineConsumer.consume(objectMapper.writeValueAsString(enrichedPayload))
            }
            
            return Response.accepted(mapOf(
                "status" to "accepted",
                "pipelineId" to pipelineId,
                "runId" to run.id,
                "message" to "Data queued for processing"
            )).build()
        } catch (e: Exception) {
            run.markFailed(e.message)
            run.persist()
            return Response.status(500)
                .entity(mapOf("error" to "Failed to queue data: ${e.message}"))
                .build()
        }
    }
    
    @GET
    @Path("/health")
    fun health(): Response {
        return Response.ok(mapOf(
            "status" to "healthy",
            "service" to "dataturntable-ingest"
        )).build()
    }
    
    @GET
    @Path("/ping")
    fun ping(): Response {
        return Response.ok("pong").build()
    }
}
