package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.connectors.Connector
import com.dataturntable.engine.PipelineConsumer
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

@Path("/pipelines")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class PipelineResource {
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @PersistenceContext
    lateinit var em: EntityManager

    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
    }

    private fun parseInstant(value: String, field: String): java.time.Instant {
        return try {
            java.time.Instant.parse(value)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
    }

    private fun parseGraphDefinition(graphDefinition: String?): Any? {
        if (graphDefinition.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(graphDefinition, Map::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun validateGraphDefinition(tenantId: UUID, graphDefinition: Any?): String? {
        if (graphDefinition !is Map<*, *>) return null
        val nodes = graphDefinition["nodes"] as? List<*> ?: return null
        for (node in nodes) {
            val nodeMap = node as? Map<*, *> ?: continue
            val nodeType = (nodeMap["type"] as? String)?.uppercase() ?: continue
            if (nodeType != "IMPORT" && nodeType != "EXPORT") continue

            val connectorId = nodeMap["connectorId"] as? String
                ?: return "IMPORT and EXPORT nodes require connectorId"
            val connector = try { em.find(Connector::class.java, UUID.fromString(connectorId)) } catch (_: Exception) { null }
                ?: return "Connector not found: $connectorId"

            if (connector.tenant?.id != tenantId) {
                return "Connector does not belong to pipeline tenant: $connectorId"
            }

            val direction = connector.direction?.name
            if (nodeType == "IMPORT" && direction !in setOf("INPUT", "BOTH")) {
                return "Connector $connectorId must have INPUT or BOTH direction for IMPORT nodes"
            }
            if (nodeType == "EXPORT" && direction !in setOf("OUTPUT", "BOTH")) {
                return "Connector $connectorId must have OUTPUT or BOTH direction for EXPORT nodes"
            }
        }
        return null
    }

    private fun pipelineToMap(p: Pipeline): Map<String, Any?> {
        return mapOf(
            "id" to p.id,
            "tenantId" to p.tenant?.id,
            "name" to p.name,
            "description" to p.description,
            "importModuleVersionId" to p.importModuleVersionId,
            "processingModuleVersionIds" to p.processingModuleVersionIds.toList(),
            "exportModuleVersionIds" to p.exportModuleVersionIds.toList(),
            "kafkaConsumerGroup" to p.kafkaConsumerGroup,
            "isActive" to p.isActive,
            "graphDefinition" to parseGraphDefinition(p.graphDefinition),
            "createdAt" to p.createdAt,
            "updatedAt" to p.updatedAt
        )
    }

    private fun latestRunSummary(pipelineId: UUID): Map<String, Any?> {
        val run = em.createQuery(
            "SELECT r FROM PipelineRun r WHERE r.pipeline.id = :pipelineId ORDER BY r.startedAt DESC",
            PipelineRun::class.java
        )
            .setParameter("pipelineId", pipelineId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

        return if (run == null) {
            emptyMap()
        } else {
            mapOf(
                "latestRunId" to run.id,
                "latestRunStatus" to run.status.name,
                "latestRunStartedAt" to run.startedAt,
                "latestRunTriggerType" to run.triggerType.name
            )
        }
    }
    
    @GET
    @Path("/runs")
    fun getTenantRuns(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("runStatus") runStatus: String?,
        @QueryParam("pipelineId") pipelineId: String?
    ): Response {
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val fromInstant = try {
            from?.let { parseInstant(it, "from") }
        } catch (e: WebApplicationException) {
            return e.response
        }
        val toInstant = try {
            to?.let { parseInstant(it, "to") }
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            return Response.status(400).entity(mapOf("error" to "from must be before to")).build()
        }

        val runStatusEnum = if (runStatus.isNullOrBlank()) {
            null
        } else {
            try {
                com.dataturntable.pipeline.PipelineRunStatus.valueOf(runStatus.uppercase())
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid runStatus")).build()
            }
        }

        val pipelineUuid = if (pipelineId.isNullOrBlank()) {
            null
        } else {
            try {
                parseUuid(pipelineId, "pipelineId")
            } catch (e: WebApplicationException) {
                return e.response
            }
        }

        val maxRows = (limit ?: 50).coerceIn(1, 200)

        val jpql = StringBuilder(
            "SELECT r FROM PipelineRun r JOIN FETCH r.pipeline p WHERE p.tenant.id = :tenantId"
        )
        if (fromInstant != null) jpql.append(" AND r.startedAt >= :from")
        if (toInstant != null) jpql.append(" AND r.startedAt <= :to")
        if (runStatusEnum != null) jpql.append(" AND r.status = :runStatus")
        if (pipelineUuid != null) jpql.append(" AND p.id = :pipelineId")
        jpql.append(" ORDER BY r.startedAt DESC")

        val query = em.createQuery(jpql.toString(), PipelineRun::class.java)
            .setParameter("tenantId", tenantUuid)
            .setMaxResults(maxRows)
        if (fromInstant != null) query.setParameter("from", fromInstant)
        if (toInstant != null) query.setParameter("to", toInstant)
        if (runStatusEnum != null) query.setParameter("runStatus", runStatusEnum)
        if (pipelineUuid != null) query.setParameter("pipelineId", pipelineUuid)

        val runs = query.resultList

        return Response.ok(
            runs.map { run ->
                mapOf(
                    "id" to run.id,
                    "pipelineId" to run.pipeline?.id,
                    "pipelineName" to run.pipeline?.name,
                    "status" to run.status.name,
                    "triggerType" to run.triggerType.name,
                    "startedAt" to run.startedAt,
                    "completedAt" to run.completedAt,
                    "durationMs" to if (run.completedAt != null) java.time.Duration.between(run.startedAt, run.completedAt).toMillis() else null,
                    "errorMessage" to run.errorMessage,
                )
            }
        ).build()
    }

    @GET
    @Path("/runs/summary")
    fun getRunSummary(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("limit") limit: Int?,
        @QueryParam("sinceHours") sinceHours: Int?
    ): Response {
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val maxRows = (limit ?: 50).coerceIn(1, 500)
        val hours = sinceHours ?: 24
        if (hours !in setOf(1, 24, 168)) {
            return Response.status(400).entity(mapOf("error" to "Invalid sinceHours")).build()
        }

        val bucketSeconds = when (hours) {
            1 -> 10 * 60L
            24 -> 60 * 60L
            else -> 6 * 60 * 60L
        }
        val bucketCount = when (hours) {
            1 -> 6
            24 -> 24
            else -> 28
        }

        val sinceInstant = java.time.Instant.now().minus(java.time.Duration.ofHours(hours.toLong()))
        val runs = em.createQuery(
            "SELECT r FROM PipelineRun r WHERE r.pipeline.tenant.id = :tenantId AND r.startedAt >= :since ORDER BY r.startedAt DESC",
            PipelineRun::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setParameter("since", sinceInstant)
            .setMaxResults(maxRows)
            .resultList

        val total = runs.size
        val success = runs.count { it.status.name == "SUCCESS" }
        val failed = runs.count { it.status.name == "FAILED" }
        val running = runs.count { it.status.name == "RUNNING" || it.status.name == "PENDING" }
        val completedDurations = runs.mapNotNull { run ->
            if (run.startedAt != null && run.completedAt != null) {
                java.time.Duration.between(run.startedAt, run.completedAt).toMillis()
            } else {
                null
            }
        }
        val avgDurationMs = if (completedDurations.isNotEmpty()) {
            completedDurations.average().toLong()
        } else {
            null
        }
        val successRate = if (total > 0) (success.toDouble() / total) else 0.0

        val buckets = MutableList(bucketCount) { index ->
            mutableMapOf<String, Any>(
                "bucketStart" to sinceInstant.plusSeconds(index.toLong() * bucketSeconds).toString(),
                "totalRuns" to 0,
                "successRuns" to 0,
                "failedRuns" to 0,
                "inFlightRuns" to 0
            )
        }

        runs.forEach { run ->
            val startedAt = run.startedAt ?: return@forEach
            val secondsFromStart = java.time.Duration.between(sinceInstant, startedAt).seconds
            if (secondsFromStart < 0) return@forEach
            val index = (secondsFromStart / bucketSeconds).toInt().coerceIn(0, bucketCount - 1)
            val bucket = buckets[index]
            bucket["totalRuns"] = (bucket["totalRuns"] as Int) + 1
            when (run.status.name) {
                "SUCCESS" -> bucket["successRuns"] = (bucket["successRuns"] as Int) + 1
                "FAILED" -> bucket["failedRuns"] = (bucket["failedRuns"] as Int) + 1
                "RUNNING", "PENDING" -> bucket["inFlightRuns"] = (bucket["inFlightRuns"] as Int) + 1
            }
        }

        return Response.ok(
            mapOf(
                "windowSize" to maxRows,
                "sinceHours" to hours,
                "bucketSeconds" to bucketSeconds,
                "totalRuns" to total,
                "successRuns" to success,
                "failedRuns" to failed,
                "inFlightRuns" to running,
                "successRate" to successRate,
                "avgDurationMs" to avgDurationMs,
                "trendBuckets" to buckets
            )
        ).build()
    }

    @GET
    fun list(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("includeRunSummary") includeRunSummary: String?,
        @QueryParam("runHealth") runHealth: String?,
        @QueryParam("latestSinceHours") latestSinceHours: Int?,
        @QueryParam("latestFrom") latestFrom: String?,
        @QueryParam("latestTo") latestTo: String?
    ): Response {
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val pipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId",
            Pipeline::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList
        val withRunSummary = includeRunSummary.equals("true", ignoreCase = true)
        val requestedHealth = runHealth?.uppercase()
        val allowedHealthFilters = setOf("ALL", "PROBLEMATIC", "RUNNING", "FAILED", "HEALTHY")
        if (requestedHealth != null && requestedHealth !in allowedHealthFilters) {
            return Response.status(400).entity(mapOf("error" to "Invalid runHealth")).build()
        }
        if (latestSinceHours != null && latestSinceHours !in setOf(1, 24, 168)) {
            return Response.status(400).entity(mapOf("error" to "Invalid latestSinceHours")).build()
        }
        val latestFromInstant = try {
            latestFrom?.let { parseInstant(it, "latestFrom") }
        } catch (e: WebApplicationException) {
            return e.response
        }
        val latestToInstant = try {
            latestTo?.let { parseInstant(it, "latestTo") }
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (latestFromInstant != null && latestToInstant != null && latestFromInstant.isAfter(latestToInstant)) {
            return Response.status(400).entity(mapOf("error" to "latestFrom must be before latestTo")).build()
        }
        val latestCutoff = latestSinceHours?.let {
            java.time.Instant.now().minus(java.time.Duration.ofHours(it.toLong()))
        }

        data class PipelineListEntry(
            val base: Map<String, Any?>,
            val summary: Map<String, Any?>
        )

        val entries = pipelines.map {
            val base = pipelineToMap(it)
            val summary = if (it.id == null) emptyMap() else latestRunSummary(it.id!!)
            PipelineListEntry(base, summary)
        }

        return Response.ok(
            entries
                .filter { entry ->
                    val pipelineMap = entry.summary
                    when (requestedHealth) {
                        null, "", "ALL" -> true
                        "PROBLEMATIC" -> {
                            val status = pipelineMap["latestRunStatus"] as? String
                            status == "FAILED" || status == "RUNNING" || status == "PENDING"
                        }
                        "RUNNING" -> {
                            val status = pipelineMap["latestRunStatus"] as? String
                            status == "RUNNING" || status == "PENDING"
                        }
                        "FAILED" -> (pipelineMap["latestRunStatus"] as? String) == "FAILED"
                        "HEALTHY" -> (pipelineMap["latestRunStatus"] as? String) == "SUCCESS"
                        else -> true
                    }
                }
                .filter { entry ->
                    if (latestCutoff == null) return@filter true
                    val startedAt = entry.summary["latestRunStartedAt"]?.toString() ?: return@filter false
                    val instant = try {
                        java.time.Instant.parse(startedAt)
                    } catch (_: Exception) {
                        return@filter false
                    }
                    !instant.isBefore(latestCutoff)
                }
                .filter { entry ->
                    if (latestFromInstant == null && latestToInstant == null) return@filter true
                    val startedAt = entry.summary["latestRunStartedAt"]?.toString() ?: return@filter false
                    val instant = try {
                        java.time.Instant.parse(startedAt)
                    } catch (_: Exception) {
                        return@filter false
                    }
                    val afterFrom = latestFromInstant?.let { !instant.isBefore(it) } ?: true
                    val beforeTo = latestToInstant?.let { !instant.isAfter(it) } ?: true
                    afterFrom && beforeTo
                }
                .map { entry ->
                    if (withRunSummary) entry.base + entry.summary else entry.base
                }
        ).build()
    }
    
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: request["tenantId"] as? String
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()

        if (tenantId.isNullOrBlank()) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }

        val tenantUuid = UUID.fromString(tenantId)
        val tenant = em.find(com.dataturntable.tenant.Tenant::class.java, tenantUuid)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()

        val graphDefinition = request["graphDefinition"]
        val graphValidationError = validateGraphDefinition(tenantUuid, graphDefinition)
        if (graphValidationError != null) {
            return Response.status(400).entity(mapOf("error" to graphValidationError)).build()
        }

        val graphDefinitionJson = graphDefinition?.let { objectMapper.writeValueAsString(it) }
        
        val pipeline = Pipeline().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.kafkaConsumerGroup = "pipeline-${java.util.UUID.randomUUID()}"
            this.isActive = false
            this.graphDefinition = graphDefinitionJson
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        pipeline.persist()
        
        return Response.status(201).entity(pipelineToMap(pipeline)).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            Response.ok(pipelineToMap(pipeline)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            pipeline.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            request["name"]?.let { pipeline.name = it as String }
            request["description"]?.let { pipeline.description = it as String }
            request["isActive"]?.let { pipeline.isActive = it as Boolean }
            request["importModuleVersionId"]?.let { pipeline.importModuleVersionId = UUID.fromString(it as String) }
            request["processingModuleVersionIds"]?.let { 
                (it as List<*>).let { ids -> 
                    pipeline.processingModuleVersionIds = ids.map { id -> UUID.fromString(id as String) }.toTypedArray()
                }
            }
            request["exportModuleVersionIds"]?.let { 
                (it as List<*>).let { ids -> 
                    pipeline.exportModuleVersionIds = ids.map { id -> UUID.fromString(id as String) }.toTypedArray()
                }
            }

            request["graphDefinition"]?.let {
                val tenantId = pipeline.tenant?.id
                    ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
                val graphValidationError = validateGraphDefinition(tenantId, it)
                if (graphValidationError != null) {
                    return Response.status(400).entity(mapOf("error" to graphValidationError)).build()
                }
                pipeline.graphDefinition = objectMapper.writeValueAsString(it)
            }
            pipeline.updatedAt = java.time.Instant.now()
            pipeline.persist()
            Response.ok(pipelineToMap(pipeline)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @POST
    @Path("/{id}/run")
    @Transactional
    fun trigger(@PathParam("id") id: String, @QueryParam("triggerType") triggerType: String?): Response {
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.triggerType = try {
                    triggerType?.let { TriggerType.valueOf(it.uppercase()) } ?: TriggerType.MANUAL
                } catch (_: IllegalArgumentException) {
                    return Response.status(400).entity(mapOf("error" to "Invalid triggerType")).build()
                }
            }
            run.persist()
            em.flush()

            val triggerMessage = mapOf(
                "runId" to run.id.toString(),
                "pipelineId" to pipeline.id.toString(),
                "triggerType" to run.triggerType.name,
                "data" to mapOf(
                    "_pipelineId" to pipeline.id.toString(),
                    "_triggerType" to run.triggerType.name
                )
            )

            try {
                pipelineConsumer.consume(objectMapper.writeValueAsString(triggerMessage))
            } catch (e: Exception) {
                run.status = com.dataturntable.pipeline.PipelineRunStatus.FAILED
                run.completedAt = java.time.Instant.now()
                run.errorMessage = e.message ?: "Failed to execute pipeline trigger"
                run.persist()
            }

            Response.status(201).entity(
                mapOf(
                    "id" to run.id,
                    "status" to run.status.name,
                    "triggerType" to run.triggerType.name,
                    "startedAt" to run.startedAt
                )
            ).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/runs")
    fun getRuns(@PathParam("id") id: String): Response {
        val pipelineId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid pipeline id")).build()
        }

        val runs = em.createQuery(
            "SELECT r FROM PipelineRun r WHERE r.pipeline.id = :pipelineId ORDER BY r.startedAt DESC",
            PipelineRun::class.java
        )
            .setParameter("pipelineId", pipelineId)
            .resultList
        return Response.ok(
            runs.map {
                val metrics = try {
                    if (it.metrics.isNullOrBlank()) emptyMap<String, Any>()
                    else objectMapper.readValue(it.metrics, Map::class.java)
                } catch (_: Exception) {
                    mapOf("raw" to it.metrics)
                }
                val durationMs = if (it.completedAt != null) {
                    java.time.Duration.between(it.startedAt, it.completedAt).toMillis()
                } else {
                    null
                }

                val result = metrics["result"] as? Map<*, *>
                val alertEvaluation = result?.get("alertEvaluation")
                val alertsFired = when (val value = result?.get("alertsFired")) {
                    is Number -> value.toInt()
                    else -> null
                }

                mapOf(
                    "id" to it.id,
                    "status" to it.status.name,
                    "triggerType" to it.triggerType.name,
                    "startedAt" to it.startedAt,
                    "completedAt" to it.completedAt,
                    "durationMs" to durationMs,
                    "errorMessage" to it.errorMessage,
                    "metrics" to metrics,
                    "alertEvaluation" to alertEvaluation,
                    "alertsFired" to alertsFired
                )
            }
        ).build()
    }
}
