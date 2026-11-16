package com.dataturntable.api

import com.dataturntable.engine.PipelineQueueUnavailableException
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.security.IngestAuthorizationService
import com.dataturntable.security.IngestRateLimiter
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
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

    @Inject
    lateinit var ingestAuthorization: IngestAuthorizationService

    @Inject
    lateinit var ingestRateLimiter: IngestRateLimiter

    @ConfigProperty(name = "dataturntable.ingest.batch-max-records", defaultValue = "1000")
    var batchMaxRecords: Int = 1000

    /**
     * Queues [payload] for one pipeline. `X-Partition-Key` (e.g. a device id) lets one
     * pipeline's load spread over all executors while keeping per-key order. Requires an API
     * key (`X-API-Key`) granted `ingest:pipeline:<id>`.
     */
    @POST
    @Path("/ingest/{pipelineId}")
    fun ingest(
        @PathParam("pipelineId") pipelineId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        @HeaderParam("X-Partition-Key") partitionKey: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        payload: Map<String, Any>
    ): Response {
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
            ?: return error(400, "Invalid pipelineId")
        val pipeline = directory.get(pipelineUuid) ?: return error(404, "Pipeline not found")
        if (!ingestAuthorization.authorize(apiKey, pipeline.tenantId, "pipeline", pipelineUuid)) {
            return error(401, "Invalid, expired, or insufficient ingestion credential")
        }
        if (!ingestRateLimiter.tryAcquire(apiKey)) return rateLimited()
        if (!pipeline.active) return error(400, "Pipeline is not active")
        tenantQuotaExceeded(pipeline, 1)?.let { return it }

        val data = payload.toMutableMap<String, Any?>()
        data["_ingestedAt"] = Instant.now().toString()
        data["_tenantId"] = pipeline.tenantId?.toString()
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
     * Batch form of [ingest] (IMP-01): a JSON array of records in one request, each queued as its
     * own run. All-or-resend: a 202 means every record is stored; a 503 means at least one is not
     * and the whole batch should be sent again. Resending is safe for identified records — each
     * record's identity is its `deduplicateBy` key, or `<Idempotency-Key>:<index>` when the header
     * is sent, so records already queued by the first attempt do not run twice. The rate limit
     * counts every record, not the request.
     */
    @POST
    @Path("/ingest/{pipelineId}/batch")
    fun ingestBatch(
        @PathParam("pipelineId") pipelineId: String,
        @HeaderParam("X-API-Key") apiKey: String?,
        @HeaderParam("X-Partition-Key") partitionKey: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        records: List<Map<String, Any>>
    ): Response {
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
            ?: return error(400, "Invalid pipelineId")
        val pipeline = directory.get(pipelineUuid) ?: return error(404, "Pipeline not found")
        if (!ingestAuthorization.authorize(apiKey, pipeline.tenantId, "pipeline", pipelineUuid)) {
            return error(401, "Invalid, expired, or insufficient ingestion credential")
        }
        if (records.isEmpty()) return error(400, "Batch is empty")
        if (records.size > batchMaxRecords) {
            IngestRejections.count("batch_too_large")
            return error(413, "Batch exceeds $batchMaxRecords records")
        }
        if (!pipeline.active) return error(400, "Pipeline is not active")
        repeat(records.size) {
            if (!ingestRateLimiter.tryAcquire(apiKey)) return rateLimited()
        }
        tenantQuotaExceeded(pipeline, records.size)?.let { return it }

        val batchKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        val ingestedAt = Instant.now().toString()
        val runIds = mutableListOf<String>()
        records.forEachIndexed { index, record ->
            val data = record.toMutableMap<String, Any?>()
            data["_ingestedAt"] = ingestedAt
            data["_tenantId"] = pipeline.tenantId?.toString()
            val key = batchKey?.let { "$it:$index" } ?: PipelineDirectory.recordKey(record, pipeline.deduplicateBy)
            try {
                runIds.add(runQueue.enqueue(pipelineUuid, TriggerType.DATA, data, partitionKey = partitionKey, idempotencyKey = key).toString())
            } catch (e: PipelineQueueUnavailableException) {
                return Response.status(503).header("Retry-After", RETRY_AFTER_SECONDS)
                    .entity(mapOf("error" to "Not all records could be queued, resend the batch",
                        "accepted" to runIds.size, "total" to records.size)).build()
            }
        }
        return Response.accepted(mapOf(
            "status" to "accepted",
            "pipelineId" to pipelineId,
            "accepted" to runIds.size,
            "runIds" to runIds
        )).build()
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
        val candidates = directory.activeForConnector(connectorUuid)
        if (candidates.isEmpty()) return error(404, "No active pipelines found for connector $connectorId")
        // A connector and its pipelines belong to one tenant; the key must be granted this connector.
        val tenant = candidates.first().tenantId
        if (!ingestAuthorization.authorize(apiKey, tenant, "connector", connectorUuid)) {
            return error(401, "Invalid, expired, or insufficient ingestion credential")
        }
        if (!ingestRateLimiter.tryAcquire(apiKey)) return rateLimited()
        val pipelines = candidates.filter { it.tenantId == tenant }
        tenantQuotaExceeded(pipelines.first(), pipelines.size)?.let { return it }

        val triggeredRuns = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<String>()
        for (pipeline in pipelines) {
            val data = payload.toMutableMap<String, Any?>()
            data["_connectorId"] = connectorId
            data["_tenantId"] = tenant?.toString()
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

    private fun rateLimited(): Response {
        IngestRejections.count("rate_limit")
        return error(429, "Ingestion rate limit exceeded")
    }

    /** TEN-10: `429` with `Retry-After` once the tenant's ingest quota for this minute is used up. */
    private fun tenantQuotaExceeded(pipeline: PipelineDirectory.Entry, records: Int): Response? {
        val retryAfter = ingestRateLimiter.tenantRetryAfter(pipeline.tenantId, pipeline.tenantIngestPerMinute, records)
        if (retryAfter == 0) return null
        IngestRejections.count("tenant_quota")
        return Response.status(429).header("Retry-After", retryAfter)
            .entity(mapOf("error" to "Tenant ingestion quota exceeded", "retryAfterSeconds" to retryAfter)).build()
    }

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
