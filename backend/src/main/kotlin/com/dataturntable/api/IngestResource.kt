package com.dataturntable.api

import com.dataturntable.connectors.Connector
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.service.KafkaService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/api/ingest/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
/**
 * Defines the class `IngestResource`, the contract implemented by this source unit.
 */
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
    /**
     * Implements `ingest` for this source unit.
     */
    fun ingest(
        @PathParam("pipelineId") pipelineId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        @HeaderParam("X-Tenant-Id") tenantId: String?,
        payload: Map<String, Any>
    ): Response {
        val pipelineUuid = try {
            UUID.fromString(pipelineId)
        } catch (_: Exception) {
            return Response.status(400)
                .entity(mapOf("error" to "Invalid pipelineId"))
                .build()
        }
        val pipeline = em.find(Pipeline::class.java, pipelineUuid)
        
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
    
    /**
     * Push-trigger endpoint: an external system or internal ingest service posts data here.
     * The platform finds all active pipelines whose graph contains the given connectorId on an
     * IMPORT node and triggers each one with the incoming payload.
     *
     * Used by: MQTT ingest, WebSocket ingest, UDP ingest, and external HTTP push sources.
     */
    @POST
    @Path("/ingest/connector/{connectorId}")
    @Transactional
    fun ingestViaConnector(
        @PathParam("connectorId") connectorId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        payload: Map<String, Any>
    ): Response {
        val connectorUuid = try {
            UUID.fromString(connectorId)
        } catch (_: Exception) {
            return Response.status(400)
                .entity(mapOf("error" to "Invalid connectorId"))
                .build()
        }

        val connector = em.find(Connector::class.java, connectorUuid)
            ?: return Response.status(404)
                .entity(mapOf("error" to "Connector not found"))
                .build()

        if (connector.direction != Connector.Direction.INPUT) {
            return Response.status(400)
                .entity(mapOf("error" to "Connector is not an INPUT connector"))
                .build()
        }

        // Find active pipelines that reference this connector on an IMPORT node.
        // We query connector_pipeline_map first (populated at pipeline save/activate),
        // then fall back to scanning graph JSON for pipelines not yet mapped.
        @Suppress("UNCHECKED_CAST")
        val mappedPipelineIds: List<UUID> = em.createNativeQuery(
            "SELECT DISTINCT pipeline_id FROM connector_pipeline_map WHERE connector_id = :cid AND node_type = 'IMPORT'"
        )
            .setParameter("cid", connectorUuid)
            .resultList as List<UUID>

        // Also scan graph JSON for any active pipelines not yet in the map (e.g. if map is stale)
        @Suppress("UNCHECKED_CAST")
        val graphMatchIds: List<UUID> = em.createQuery(
            "SELECT p.id FROM Pipeline p WHERE p.isActive = true AND p.graphDefinition LIKE :pattern",
            UUID::class.java
        )
            .setParameter("pattern", "%\"connectorId\":\"$connectorId\"%")
            .resultList

        val pipelineIds = (mappedPipelineIds + graphMatchIds).distinct()

        if (pipelineIds.isEmpty()) {
            return Response.status(404)
                .entity(mapOf("error" to "No active pipelines found for connector $connectorId"))
                .build()
        }

        val triggeredRuns = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<String>()

        for (pid in pipelineIds) {
            val pipeline = em.find(Pipeline::class.java, pid) ?: continue
            if (!pipeline.isActive) continue

            val enrichedPayload = payload.toMutableMap()
            enrichedPayload["_connectorId"] = connectorId
            enrichedPayload["_ingestedAt"] = java.time.Instant.now().toString()

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.triggerType = TriggerType.DATA
            }
            run.persist()
            em.flush()
            enrichedPayload["_runId"] = run.id.toString()
            enrichedPayload["_pipelineId"] = pid.toString()

            try {
                kafkaService.sendToPipelineSpecificInput(pid.toString(), enrichedPayload)
                if (!kafkaService.isKafkaAvailable()) {
                    pipelineConsumer.consume(objectMapper.writeValueAsString(enrichedPayload))
                }
                triggeredRuns.add(mapOf("pipelineId" to pid, "runId" to run.id))
            } catch (e: Exception) {
                run.markFailed(e.message)
                run.persist()
                errors.add("Pipeline $pid: ${e.message}")
            }
        }

        return if (triggeredRuns.isNotEmpty()) {
            Response.accepted(mapOf(
                "status" to "accepted",
                "connectorId" to connectorId,
                "triggeredPipelines" to triggeredRuns,
                "errors" to errors.takeIf { it.isNotEmpty() }
            )).build()
        } else {
            Response.status(500)
                .entity(mapOf(
                    "error" to "All pipeline triggers failed",
                    "errors" to errors
                ))
                .build()
        }
    }

    @GET
    @Path("/health")
    /**
     * Implements `health` for this source unit.
     */
    fun health(): Response {
        return Response.ok(mapOf(
            "status" to "healthy",
            "service" to "dataturntable-ingest"
        )).build()
    }
    
    @GET
    @Path("/ping")
    /**
     * Implements `ping` for this source unit.
     */
    fun ping(): Response {
        return Response.ok("pong").build()
    }
}
