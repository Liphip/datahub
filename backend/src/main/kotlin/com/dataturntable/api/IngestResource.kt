package com.dataturntable.api

import com.dataturntable.engine.PipelineQueueUnavailableException
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.pipeline.TriggerType
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

/**
 * HTTP push ingestion. A 202 means the record is stored on the Kafka brokers; a 503 means
 * it is not and the client should send it again. Neither path touches the database, so only
 * Kafka losing its write quorum makes clients resend.
 *
 * Exactly once: send an `Idempotency-Key` header (or configure `deduplicateBy` fields on the
 * pipeline's IMPORT node). A record resent with the same key gets the same run id and is
 * processed only once, however often it arrives.
 */
@Path("/api/ingest/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class IngestResource {

    @Inject
    lateinit var runQueue: PipelineRunQueue

    @Inject
    lateinit var directory: PipelineDirectory

    /**
     * Queues [payload] for one pipeline. `X-Partition-Key` (e.g. a device id) lets one
     * pipeline's load spread over all executors while keeping per-key order.
     */
    @POST
    @Path("/ingest/{pipelineId}")
    fun ingest(
        @PathParam("pipelineId") pipelineId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        @HeaderParam("X-Tenant-Id") tenantId: String?,
        @HeaderParam("X-Partition-Key") partitionKey: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        payload: Map<String, Any>
    ): Response {
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
            ?: return error(400, "Invalid pipelineId")
        val pipeline = directory.get(pipelineUuid) ?: return error(404, "Pipeline not found")
        if (!pipeline.active) return error(400, "Pipeline is not active")

        val data = payload.toMutableMap<String, Any?>()
        data["_ingestedAt"] = Instant.now().toString()
        tenantId?.let { data["_tenantId"] = it }
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() } ?: PipelineDirectory.recordKey(payload, pipeline.deduplicateBy)

        return try {
            val runId = runQueue.enqueue(pipelineUuid, TriggerType.DATA, data, partitionKey = partitionKey, idempotencyKey = key)
            Response.accepted(mapOf(
                "status" to "accepted",
                "pipelineId" to pipelineId,
                "runId" to runId,
                "idempotent" to (key != null),
                "message" to "Data queued for processing"
            )).build()
        } catch (e: PipelineQueueUnavailableException) {
            unavailable(e.message)
        }
    }

    /**
     * Push-trigger endpoint: an external system or internal ingest service posts data here.
     * The platform finds all active pipelines whose graph contains the given connectorId on an
     * IMPORT node and triggers each one with the incoming payload. If any pipeline could not
     * be queued the response is 503 and the client resends; with an idempotency key the
     * pipelines that were already queued do not run twice.
     */
    @POST
    @Path("/ingest/connector/{connectorId}")
    fun ingestViaConnector(
        @PathParam("connectorId") connectorId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        @HeaderParam("X-Partition-Key") partitionKey: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        payload: Map<String, Any>
    ): Response {
        val connectorUuid = runCatching { UUID.fromString(connectorId) }.getOrNull()
            ?: return error(400, "Invalid connectorId")
        val pipelines = directory.activeForConnector(connectorUuid)
        if (pipelines.isEmpty()) return error(404, "No active pipelines found for connector $connectorId")

        val triggeredRuns = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<String>()
        for (pipeline in pipelines) {
            val data = payload.toMutableMap<String, Any?>()
            data["_connectorId"] = connectorId
            data["_ingestedAt"] = Instant.now().toString()
            val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() } ?: PipelineDirectory.recordKey(payload, pipeline.deduplicateBy)
            try {
                val runId = runQueue.enqueue(pipeline.id, TriggerType.DATA, data, partitionKey = partitionKey, idempotencyKey = key)
                triggeredRuns.add(mapOf("pipelineId" to pipeline.id, "runId" to runId))
            } catch (e: PipelineQueueUnavailableException) {
                errors.add("Pipeline ${pipeline.id}: ${e.message}")
            }
        }

        if (errors.isNotEmpty()) {
            return Response.status(503)
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .entity(mapOf("error" to "Not all pipelines could be queued, resend the record",
                    "triggeredPipelines" to triggeredRuns, "errors" to errors))
                .build()
        }
        return Response.accepted(mapOf(
            "status" to "accepted",
            "connectorId" to connectorId,
            "triggeredPipelines" to triggeredRuns
        )).build()
    }

    private fun error(status: Int, message: String): Response =
        Response.status(status).entity(mapOf("error" to message)).build()

    private fun unavailable(message: String?): Response =
        Response.status(503).header("Retry-After", RETRY_AFTER_SECONDS)
            .entity(mapOf("error" to (message ?: "Pipeline queue unavailable"))).build()

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

    companion object {
        private const val RETRY_AFTER_SECONDS = 5
    }
}
