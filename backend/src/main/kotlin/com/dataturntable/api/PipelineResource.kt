package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.connectors.Connector
import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/pipelines")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class PipelineResource {
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    private fun currentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

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
        val nodeIds = mutableSetOf<String>()
        for (node in nodes) {
            val nodeMap = node as? Map<*, *> ?: continue
            val nodeId = nodeMap["id"]?.toString() ?: "unknown-node"
            nodeIds.add(nodeId)
            val nodeType = (nodeMap["type"] as? String)?.uppercase() ?: continue
            if (nodeType !in setOf("IMPORT", "PROCESSING", "ALERT", "EXPORT")) {
                return "Node $nodeId has unsupported type: $nodeType"
            }

            val moduleVersionId = nodeMap["moduleVersionId"] as? String
                ?: return "Node $nodeId ($nodeType) requires moduleVersionId"
            val moduleVersionUuid = try {
                UUID.fromString(moduleVersionId)
            } catch (_: Exception) {
                return "Node $nodeId has invalid moduleVersionId: $moduleVersionId"
            }
            val moduleVersion = em.find(ModuleVersion::class.java, moduleVersionUuid)
                ?: return "Node $nodeId references missing module version: $moduleVersionId"
            val module = moduleVersion.module
                ?: return "Node $nodeId references module version without module"
            if (module.tenant?.id != tenantId) {
                return "Node $nodeId references module from another tenant"
            }
            val expectedType = try {
                ModuleType.valueOf(nodeType)
            } catch (_: Exception) {
                null
            }
            if (expectedType != null && module.moduleType != expectedType) {
                return "Node $nodeId expects $nodeType module version, found ${module.moduleType?.name ?: "unknown"}"
            }

            if (nodeType != "IMPORT" && nodeType != "EXPORT") continue

            val connectorId = nodeMap["connectorId"] as? String
                ?: return "Node $nodeId ($nodeType) requires connectorId"
            val connectorUuid = try {
                UUID.fromString(connectorId)
            } catch (_: Exception) {
                return "Node $nodeId has invalid connectorId: $connectorId"
            }
            val connector = em.find(Connector::class.java, connectorUuid)
                ?: return "Node $nodeId references missing connector: $connectorId"

            if (connector.tenant?.id != tenantId) {
                return "Node $nodeId references connector from another tenant"
            }

            val direction = connector.direction?.name
            if (nodeType == "IMPORT" && direction !in setOf("INPUT", "BOTH")) {
                return "Node $nodeId requires INPUT/BOTH connector direction (found $direction)"
            }
            if (nodeType == "EXPORT" && direction !in setOf("OUTPUT", "BOTH")) {
                return "Node $nodeId requires OUTPUT/BOTH connector direction (found $direction)"
            }
        }

        val edges = graphDefinition["edges"] as? List<*> ?: emptyList<Any>()
        val indegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()
        nodeIds.forEach { nodeId ->
            indegree[nodeId] = 0
            adjacency[nodeId] = mutableListOf()
        }

        for (edge in edges) {
            val edgeMap = edge as? Map<*, *> ?: continue
            val fromId = edgeMap["from"]?.toString() ?: continue
            val toId = edgeMap["to"]?.toString() ?: continue
            if (fromId !in nodeIds || toId !in nodeIds) {
                return "Edge $fromId -> $toId references unknown node"
            }
            adjacency[fromId]?.add(toId)
            indegree[toId] = (indegree[toId] ?: 0) + 1
        }

        val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visitedCount = 0
        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            visitedCount += 1
            adjacency[nodeId].orEmpty().forEach { nextId ->
                val updated = (indegree[nextId] ?: 0) - 1
                indegree[nextId] = updated
                if (updated == 0) {
                    queue.add(nextId)
                }
            }
        }
        if (visitedCount != nodeIds.size) {
            return "Graph contains a cycle; pipelines must be acyclic"
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

    private fun requireTenantAccess(
        user: User,
        tenantId: String,
        ability: String,
        forbiddenMessage: String = "Forbidden",
        insufficientMessage: String = "Insufficient permissions"
    ): Response? {
        if (!SecurityUtils.canAccessTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to forbiddenMessage)).build()
        }
        if (!SecurityUtils.hasAbility(user, ability)) {
            return Response.status(403).entity(mapOf("error" to insufficientMessage)).build()
        }
        return null
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }

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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }

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
    @Path("/{id}/runs/summary")
    fun getPipelineRunSummary(
        @PathParam("id") id: String,
        @QueryParam("sinceHours") sinceHours: Int?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid pipeline id")).build()
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
            ?: return Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        val tenantId = pipeline.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }

        val hours = sinceHours ?: 24
        if (hours !in setOf(1, 24, 168)) {
            return Response.status(400).entity(mapOf("error" to "Invalid sinceHours"))
                .build()
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
            "SELECT r FROM PipelineRun r WHERE r.pipeline.id = :pipelineId AND r.startedAt >= :since ORDER BY r.startedAt DESC",
            PipelineRun::class.java
        )
            .setParameter("pipelineId", pipelineId)
            .setParameter("since", sinceInstant)
            .setMaxResults(500)
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
                "pipelineId" to pipeline.id,
                "pipelineName" to pipeline.name,
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }

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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantId = tenantIdQuery ?: request["tenantId"] as? String
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()

        if (tenantId.isNullOrBlank()) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }

        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        requireTenantAccess(currentUser, tenantId, "pipeline:write")?.let { return it }
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            val tenantId = pipeline.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
            requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }
            Response.ok(pipelineToMap(pipeline)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            val tenantId = pipeline.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
            requireTenantAccess(currentUser, tenantId, "pipeline:write")?.let { return it }
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            val tenantId = pipeline.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
            requireTenantAccess(currentUser, tenantId, "pipeline:write")?.let { return it }
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
    
    private fun enqueuePipelineRun(
        pipeline: Pipeline,
        triggerType: TriggerType,
        extraData: Map<String, Any?> = emptyMap(),
        retryOfRunId: UUID? = null
    ): PipelineRun {
        val run = PipelineRun().apply {
            this.pipeline = pipeline
            this.triggerType = triggerType
            this.retryOfRunId = retryOfRunId
        }
        run.persist()
        em.flush()

        val triggerData = mutableMapOf<String, Any?>(
            "_pipelineId" to pipeline.id.toString(),
            "_triggerType" to run.triggerType.name
        )
        triggerData.putAll(extraData)
        val triggerMessage = mapOf(
            "runId" to run.id.toString(),
            "pipelineId" to pipeline.id.toString(),
            "triggerType" to run.triggerType.name,
            "data" to triggerData
        )

        try {
            pipelineConsumer.consume(objectMapper.writeValueAsString(triggerMessage))
        } catch (e: Exception) {
            run.status = PipelineRunStatus.FAILED
            run.completedAt = java.time.Instant.now()
            run.errorMessage = e.message ?: "Failed to execute pipeline trigger"
            run.persist()
        }

        return run
    }

    @POST
    @Path("/{id}/run")
    @Transactional
    fun trigger(@PathParam("id") id: String, @QueryParam("triggerType") triggerType: String?): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val pipeline = em.find(Pipeline::class.java, pipelineId)
        return if (pipeline != null) {
            val tenantId = pipeline.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
            requireTenantAccess(currentUser, tenantId, "pipeline:execute")?.let { return it }
            val parsedTriggerType = try {
                triggerType?.let { TriggerType.valueOf(it.uppercase()) } ?: TriggerType.MANUAL
            } catch (_: IllegalArgumentException) {
                return Response.status(400).entity(mapOf("error" to "Invalid triggerType")).build()
            }
            val run = enqueuePipelineRun(pipeline, parsedTriggerType)

            Response.status(201).entity(
                mapOf(
                    "id" to run.id,
                    "status" to run.status.name,
                    "triggerType" to run.triggerType.name,
                    "startedAt" to run.startedAt,
                    "retryOfRunId" to run.retryOfRunId
                )
            ).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }

    @POST
    @Path("/{id}/runs/{runId}/retry")
    @Transactional
    fun retryRun(@PathParam("id") id: String, @PathParam("runId") runId: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val runUuid = try {
            parseUuid(runId, "run id")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
            ?: return Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        val tenantId = pipeline.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "pipeline:execute")?.let { return it }

        val sourceRun = em.find(PipelineRun::class.java, runUuid)
            ?: return Response.status(404).entity(mapOf("error" to "Run not found")).build()
        if (sourceRun.pipeline?.id != pipeline.id) {
            return Response.status(404).entity(mapOf("error" to "Run not found for this pipeline")).build()
        }
        if (sourceRun.status == PipelineRunStatus.PENDING || sourceRun.status == PipelineRunStatus.RUNNING) {
            return Response.status(409).entity(mapOf("error" to "Run is still in progress and cannot be retried yet")).build()
        }

        val retriedRun = enqueuePipelineRun(
            pipeline,
            sourceRun.triggerType,
            mapOf("_retryOfRunId" to sourceRun.id.toString()),
            sourceRun.id
        )

        return Response.status(201).entity(
            mapOf(
                "id" to retriedRun.id,
                "status" to retriedRun.status.name,
                "triggerType" to retriedRun.triggerType.name,
                "startedAt" to retriedRun.startedAt,
                "retryOfRunId" to sourceRun.id
            )
        ).build()
    }

    @POST
    @Path("/{id}/runs/{runId}/cancel")
    @Transactional
    fun cancelRun(@PathParam("id") id: String, @PathParam("runId") runId: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            parseUuid(id, "pipeline id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val runUuid = try {
            parseUuid(runId, "run id")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
            ?: return Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        val tenantId = pipeline.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "pipeline:execute")?.let { return it }

        val run = em.find(PipelineRun::class.java, runUuid)
            ?: return Response.status(404).entity(mapOf("error" to "Run not found")).build()
        if (run.pipeline?.id != pipeline.id) {
            return Response.status(404).entity(mapOf("error" to "Run not found for this pipeline")).build()
        }

        if (run.status == PipelineRunStatus.SUCCESS || run.status == PipelineRunStatus.FAILED || run.status == PipelineRunStatus.CANCELLED) {
            return Response.status(409).entity(mapOf("error" to "Run is already completed")).build()
        }

        run.status = PipelineRunStatus.CANCELLED
        run.completedAt = java.time.Instant.now()
        run.errorMessage = "Cancelled by user"
        run.persist()

        return Response.ok(
            mapOf(
                "id" to run.id,
                "status" to run.status.name,
                "completedAt" to run.completedAt,
                "errorMessage" to run.errorMessage,
                "retryOfRunId" to run.retryOfRunId
            )
        ).build()
    }
    
    @GET
    @Path("/{id}/runs")
    fun getRuns(
        @PathParam("id") id: String,
        @QueryParam("limit") limit: Int?,
        @QueryParam("includeDetails") includeDetails: Boolean?,
        @QueryParam("startedFrom") startedFrom: String?,
        @QueryParam("startedTo") startedTo: String?,
        @QueryParam("runStatus") runStatus: String?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid pipeline id")).build()
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
            ?: return Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        val tenantId = pipeline.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }
        val maxRows = (limit ?: 20).coerceIn(1, 200)
        val withDetails = includeDetails != false
        val fromInstant = if (startedFrom.isNullOrBlank()) {
            null
        } else {
            try {
                java.time.Instant.parse(startedFrom)
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid startedFrom; expected ISO-8601 timestamp")).build()
            }
        }
        val toInstant = if (startedTo.isNullOrBlank()) {
            null
        } else {
            try {
                java.time.Instant.parse(startedTo)
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid startedTo; expected ISO-8601 timestamp")).build()
            }
        }
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            return Response.status(400).entity(mapOf("error" to "startedFrom must be before startedTo")).build()
        }
        val runStatusEnum = if (runStatus.isNullOrBlank()) {
            null
        } else {
            try {
                PipelineRunStatus.valueOf(runStatus.uppercase())
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid runStatus")).build()
            }
        }

        val jpql = StringBuilder("SELECT r FROM PipelineRun r WHERE r.pipeline.id = :pipelineId")
        if (fromInstant != null) jpql.append(" AND r.startedAt >= :from")
        if (toInstant != null) jpql.append(" AND r.startedAt <= :to")
        if (runStatusEnum != null) jpql.append(" AND r.status = :runStatus")
        jpql.append(" ORDER BY r.startedAt DESC")

        val query = em.createQuery(jpql.toString(), PipelineRun::class.java)
            .setParameter("pipelineId", pipelineId)
            .setMaxResults(maxRows)
        if (fromInstant != null) query.setParameter("from", fromInstant)
        if (toInstant != null) query.setParameter("to", toInstant)
        if (runStatusEnum != null) query.setParameter("runStatus", runStatusEnum)

        val runs = query.resultList
        return Response.ok(
            runs.map {
                val metrics = if (withDetails) {
                    try {
                        if (it.metrics.isNullOrBlank()) emptyMap<String, Any>()
                        else objectMapper.readValue(it.metrics, Map::class.java)
                    } catch (_: Exception) {
                        mapOf("raw" to it.metrics)
                    }
                } else {
                    emptyMap<String, Any>()
                }
                val durationMs = if (it.completedAt != null) {
                    java.time.Duration.between(it.startedAt, it.completedAt).toMillis()
                } else {
                    null
                }

                val result = if (withDetails) metrics["result"] as? Map<*, *> else null
                val alertEvaluation = if (withDetails) result?.get("alertEvaluation") else null
                val alertsFired = if (withDetails) {
                    when (val value = result?.get("alertsFired")) {
                        is Number -> value.toInt()
                        else -> null
                    }
                } else {
                    null
                }
                val moduleLogs = if (withDetails) metrics["moduleLogs"] else null
                val executionLog = if (withDetails) metrics["executionLog"] else null

                mapOf(
                    "id" to it.id,
                    "status" to it.status.name,
                    "triggerType" to it.triggerType.name,
                    "retryOfRunId" to it.retryOfRunId,
                    "startedAt" to it.startedAt,
                    "completedAt" to it.completedAt,
                    "durationMs" to durationMs,
                    "errorMessage" to it.errorMessage,
                    "metrics" to metrics,
                    "moduleLogs" to moduleLogs,
                    "executionLog" to executionLog,
                    "alertEvaluation" to alertEvaluation,
                    "alertsFired" to alertsFired
                )
            }
        ).build()
    }

    @GET
    @Path("/{id}/runs/{runId}")
    fun getRun(
        @PathParam("id") id: String,
        @PathParam("runId") runId: String,
        @QueryParam("includeDetails") includeDetails: Boolean?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid pipeline id")).build()
        }
        val runUuid = try {
            UUID.fromString(runId)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid run id")).build()
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
            ?: return Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        val tenantId = pipeline.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }

        val run = em.find(PipelineRun::class.java, runUuid)
            ?: return Response.status(404).entity(mapOf("error" to "Run not found")).build()
        if (run.pipeline?.id != pipeline.id) {
            return Response.status(404).entity(mapOf("error" to "Run not found for this pipeline")).build()
        }

        val withDetails = includeDetails != false
        val metrics = if (withDetails) {
            try {
                if (run.metrics.isNullOrBlank()) emptyMap<String, Any>()
                else objectMapper.readValue(run.metrics, Map::class.java)
            } catch (_: Exception) {
                mapOf("raw" to run.metrics)
            }
        } else {
            emptyMap<String, Any>()
        }
        val durationMs = if (run.completedAt != null) {
            java.time.Duration.between(run.startedAt, run.completedAt).toMillis()
        } else {
            null
        }
        val result = if (withDetails) metrics["result"] as? Map<*, *> else null
        val alertEvaluation = if (withDetails) result?.get("alertEvaluation") else null
        val alertsFired = if (withDetails) {
            when (val value = result?.get("alertsFired")) {
                is Number -> value.toInt()
                else -> null
            }
        } else {
            null
        }
        val moduleLogs = if (withDetails) metrics["moduleLogs"] else null
        val executionLog = if (withDetails) metrics["executionLog"] else null

        return Response.ok(
            mapOf(
                "id" to run.id,
                "status" to run.status.name,
                "triggerType" to run.triggerType.name,
                "retryOfRunId" to run.retryOfRunId,
                "startedAt" to run.startedAt,
                "completedAt" to run.completedAt,
                "durationMs" to durationMs,
                "errorMessage" to run.errorMessage,
                "metrics" to metrics,
                "moduleLogs" to moduleLogs,
                "executionLog" to executionLog,
                "alertEvaluation" to alertEvaluation,
                "alertsFired" to alertsFired
            )
        ).build()
    }

    @GET
    @Path("/{id}/runs/{runId}/lineage")
    fun getRunLineage(
        @PathParam("id") id: String,
        @PathParam("runId") runId: String,
        @QueryParam("maxDepth") maxDepth: Int?,
        @QueryParam("direction") direction: String?,
        @QueryParam("statuses") statuses: String?,
        @QueryParam("statusPreset") statusPreset: String?,
        @QueryParam("includeDetails") includeDetails: Boolean?,
        @QueryParam("safeScopePolicy") safeScopePolicy: String?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val pipelineId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid pipeline id")).build()
        }
        val runUuid = try {
            UUID.fromString(runId)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid run id")).build()
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
            ?: return Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        val tenantId = pipeline.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Pipeline tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "pipeline:read")?.let { return it }

        val run = em.find(PipelineRun::class.java, runUuid)
            ?: return Response.status(404).entity(mapOf("error" to "Run not found")).build()
        if (run.pipeline?.id != pipeline.id) {
            return Response.status(404).entity(mapOf("error" to "Run not found for this pipeline")).build()
        }
        val depthLimit = (maxDepth ?: 20).coerceIn(1, 200)
        val normalizedDirection = when (direction?.trim()?.lowercase()) {
            null, "", "both" -> "both"
            "ancestors" -> "ancestors"
            "descendants" -> "descendants"
            else -> return Response.status(400)
                .entity(mapOf("error" to "Invalid direction. Allowed values: both, ancestors, descendants"))
                .build()
        }
        val includeAncestors = normalizedDirection != "descendants"
        val includeDescendants = normalizedDirection != "ancestors"
        val normalizedStatusPreset = when (statusPreset?.trim()?.lowercase()) {
            null, "", "all" -> "all"
            "failures" -> "failures"
            "inflight" -> "inflight"
            "terminal" -> "terminal"
            else -> return Response.status(400)
                .entity(mapOf("error" to "Invalid statusPreset. Allowed values: all, failures, inflight, terminal"))
                .build()
        }
        val statusFilter: Set<PipelineRunStatus>? = if (!statuses.isNullOrBlank()) {
            val parsed = statuses
                .split(',')
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map {
                    try {
                        PipelineRunStatus.valueOf(it.uppercase())
                    } catch (_: IllegalArgumentException) {
                        return Response.status(400)
                            .entity(mapOf("error" to "Invalid statuses filter. Allowed values: PENDING, RUNNING, SUCCESS, FAILED, CANCELLED"))
                            .build()
                    }
                }
                .toSet()
            if (parsed.isEmpty()) {
                return Response.status(400)
                    .entity(mapOf("error" to "Invalid statuses filter. Provide at least one status value"))
                    .build()
            }
            parsed
        } else {
            when (normalizedStatusPreset) {
                "failures" -> setOf(PipelineRunStatus.FAILED)
                "inflight" -> setOf(PipelineRunStatus.RUNNING, PipelineRunStatus.PENDING)
                "terminal" -> setOf(PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILED, PipelineRunStatus.CANCELLED)
                else -> null
            }
        }
        val resolvedStatusPreset = if (!statuses.isNullOrBlank()) "custom" else normalizedStatusPreset
        val normalizedSafeScopePolicy = when (safeScopePolicy?.trim()?.lowercase()) {
            null, "", "auto" -> "AUTO"
            "run" -> "RUN"
            "pipeline" -> "PIPELINE"
            else -> return Response.status(400)
                .entity(mapOf("error" to "Invalid safeScopePolicy. Allowed values: auto, run, pipeline"))
                .build()
        }
        val orderedStatuses = statusFilter
            ?.let { selected -> PipelineRunStatus.entries.filter { selected.contains(it) } }
            ?.map { it.name }
        val defaultDepth = 20
        val withDetails = includeDetails == true
        val lineageStartedNs = System.nanoTime()

        fun includeByStatus(status: PipelineRunStatus): Boolean = statusFilter?.contains(status) ?: true

        fun runSummary(item: PipelineRun, lineageDepth: Int? = null): Map<String, Any?> {
            val metrics = if (withDetails) {
                try {
                    if (item.metrics.isNullOrBlank()) emptyMap<String, Any>()
                    else objectMapper.readValue(item.metrics, Map::class.java) as Map<String, Any>
                } catch (_: Exception) {
                    mapOf("raw" to item.metrics)
                }
            } else {
                emptyMap<String, Any>()
            }
            val durationMs = if (item.completedAt != null) {
                java.time.Duration.between(item.startedAt, item.completedAt).toMillis()
            } else {
                null
            }
            val result = if (withDetails) metrics["result"] as? Map<*, *> else null
            val alertEvaluation = if (withDetails) result?.get("alertEvaluation") else null
            val alertsFired = if (withDetails) {
                when (val value = result?.get("alertsFired")) {
                    is Number -> value.toInt()
                    else -> null
                }
            } else {
                null
            }
            val moduleLogs = if (withDetails) metrics["moduleLogs"] else null
            val executionLog = if (withDetails) metrics["executionLog"] else null
            val summary = mapOf(
                "id" to item.id,
                "status" to item.status.name,
                "triggerType" to item.triggerType.name,
                "retryOfRunId" to item.retryOfRunId,
                "startedAt" to item.startedAt,
                "completedAt" to item.completedAt,
                "durationMs" to durationMs,
                "errorMessage" to item.errorMessage,
                "metrics" to metrics,
                "moduleLogs" to moduleLogs,
                "executionLog" to executionLog,
                "alertEvaluation" to alertEvaluation,
                "alertsFired" to alertsFired
            )
            return if (lineageDepth == null) summary else summary + mapOf("lineageDepth" to lineageDepth)
        }

        val ancestors = mutableListOf<Map<String, Any?>>()
        var ancestorsTotal = 0
        val ancestorsStatusCounts = mutableMapOf<String, Int>()
        var currentRetryId = run.retryOfRunId
        var ancestorDepth = -1
        var guard = 0
        var ancestorsTruncated = false
        if (includeAncestors) {
            while (currentRetryId != null && guard < depthLimit) {
                val ancestor = em.find(PipelineRun::class.java, currentRetryId) ?: break
                if (ancestor.pipeline?.id != pipeline.id) break
                ancestorsTotal += 1
                ancestorsStatusCounts[ancestor.status.name] = (ancestorsStatusCounts[ancestor.status.name] ?: 0) + 1
                if (includeByStatus(ancestor.status)) {
                    ancestors.add(runSummary(ancestor, ancestorDepth))
                }
                currentRetryId = ancestor.retryOfRunId
                ancestorDepth -= 1
                guard += 1
            }
            if (currentRetryId != null && guard >= depthLimit) {
                ancestorsTruncated = true
            }
        }

        val descendants = mutableListOf<Map<String, Any?>>()
        var descendantsTotal = 0
        val descendantsStatusCounts = mutableMapOf<String, Int>()
        var latestDescendant: PipelineRun? = null
        val queue = ArrayDeque<Pair<UUID, Int>>()
        val frontierAtDepthLimit = mutableSetOf<UUID>()
        run.id?.let { queue.add(it to 0) }
        var visited = 0
        var descendantsTruncated = false
        if (includeDescendants) {
            while (queue.isNotEmpty() && visited < (depthLimit * 10)) {
                val (parentId, parentDepth) = queue.removeFirst()
                if (parentDepth >= depthLimit) {
                    frontierAtDepthLimit.add(parentId)
                    continue
                }
                val children = em.createQuery(
                    "SELECT r FROM PipelineRun r WHERE r.pipeline.id = :pipelineId AND r.retryOfRunId = :retryOf ORDER BY r.startedAt ASC",
                    PipelineRun::class.java
                )
                    .setParameter("pipelineId", pipeline.id)
                    .setParameter("retryOf", parentId)
                    .resultList
                children.forEach { child ->
                    descendantsTotal += 1
                    descendantsStatusCounts[child.status.name] = (descendantsStatusCounts[child.status.name] ?: 0) + 1
                    if (includeByStatus(child.status)) {
                        descendants.add(runSummary(child, parentDepth + 1))
                    }
                    if (latestDescendant == null || child.startedAt.isAfter(latestDescendant?.startedAt)) {
                        latestDescendant = child
                    }
                    child.id?.let { queue.add(it to (parentDepth + 1)) }
                }
                visited += 1
            }
            if (queue.isNotEmpty()) {
                descendantsTruncated = true
            } else if (frontierAtDepthLimit.isNotEmpty()) {
                val descendantsBeyondDepth = em.createQuery(
                    "SELECT COUNT(r) FROM PipelineRun r WHERE r.pipeline.id = :pipelineId AND r.retryOfRunId IN :frontier",
                    java.lang.Long::class.java
                )
                    .setParameter("pipelineId", pipeline.id)
                    .setParameter("frontier", frontierAtDepthLimit)
                    .singleResult > 0
                descendantsTruncated = descendantsTruncated || descendantsBeyondDepth
            }
        }

        val rootRunId = ancestors.lastOrNull()?.get("id") ?: run.id
        val latestDescendantRunId = if (includeDescendants) latestDescendant?.id else null
        val rootRunInView = rootRunId == run.id || ancestors.any { it["id"] == rootRunId }
        val latestDescendantRunInView = latestDescendantRunId != null && descendants.any { it["id"] == latestDescendantRunId }
        val lineageQueryMs = (System.nanoTime() - lineageStartedNs) / 1_000_000
        val buildStartedNs = System.nanoTime()
        val isLineageTruncated = ancestorsTruncated || descendantsTruncated
        val estimatedNodesInResponse = 1 + ancestors.size + descendants.size
        val estimatedPayloadBytes = estimatedNodesInResponse * if (withDetails) 2200 else 260
        val estimatedPayloadClass = when {
            estimatedPayloadBytes < 50_000 -> "SMALL"
            estimatedPayloadBytes < 200_000 -> "MEDIUM"
            else -> "LARGE"
        }
        val nextRecommendedDepth = if (isLineageTruncated && depthLimit < 200) {
            minOf(200, if (depthLimit < 20) 20 else depthLimit + 20)
        } else {
            null
        }
        val lineageBuildMs = (System.nanoTime() - buildStartedNs) / 1_000_000
        val lineageTotalMs = (System.nanoTime() - lineageStartedNs) / 1_000_000
        val lineageMeasuredAt = java.time.Instant.now()
        val heavyQuery = lineageTotalMs >= 250
        val heavyPayload = estimatedPayloadClass == "LARGE"
        val lineagePerformanceHint = when {
            heavyQuery && heavyPayload -> "HEAVY_BOTH"
            heavyQuery -> "HEAVY_QUERY"
            heavyPayload -> "HEAVY_PAYLOAD"
            else -> "NORMAL"
        }
        val recommendedAction = when {
            withDetails && heavyPayload -> "SWITCH_TO_SUMMARY"
            heavyQuery && depthLimit > defaultDepth -> "REDUCE_DEPTH"
            else -> "NONE"
        }
        val recommendedSafeFetchMode = if (recommendedAction == "SWITCH_TO_SUMMARY") "SUMMARY" else if (withDetails) "DETAILS" else "SUMMARY"
        val recommendedSafeDepth = if (recommendedAction == "REDUCE_DEPTH") {
            maxOf(defaultDepth, depthLimit - 20)
        } else {
            depthLimit
        }
        val recommendedSafeReason = if (recommendedAction == "NONE") null else recommendedAction
        val recommendedSafeTtlHours = 168
        val recommendedSafeScope = when {
            recommendedAction == "NONE" -> "RUN"
            heavyQuery || heavyPayload -> "PIPELINE"
            else -> "RUN"
        }
        val appliedSafeScope = if (normalizedSafeScopePolicy == "AUTO") recommendedSafeScope else normalizedSafeScopePolicy

        return Response.ok(
            mapOf(
                "run" to runSummary(run),
                "ancestors" to ancestors,
                "descendants" to descendants,
                "rootRunId" to rootRunId,
                "latestDescendantRunId" to latestDescendantRunId,
                "maxDepth" to depthLimit,
                "defaultDepth" to defaultDepth,
                "includeDetails" to withDetails,
                "direction" to normalizedDirection,
                "statuses" to orderedStatuses,
                "statusPreset" to resolvedStatusPreset,
                "statusFiltered" to (statusFilter != null),
                "rootRunInView" to rootRunInView,
                "latestDescendantRunInView" to latestDescendantRunInView,
                "estimatedNodesInResponse" to estimatedNodesInResponse,
                "estimatedPayloadBytes" to estimatedPayloadBytes,
                "estimatedPayloadClass" to estimatedPayloadClass,
                "lineageQueryMs" to lineageQueryMs,
                "lineageBuildMs" to lineageBuildMs,
                "lineageTotalMs" to lineageTotalMs,
                "lineageMeasuredAt" to lineageMeasuredAt,
                "lineagePerformanceHint" to lineagePerformanceHint,
                "recommendedAction" to recommendedAction,
                "recommendedSafeFetchMode" to recommendedSafeFetchMode,
                "recommendedSafeDepth" to recommendedSafeDepth,
                "recommendedSafeReason" to recommendedSafeReason,
                "recommendedSafeTtlHours" to recommendedSafeTtlHours,
                "recommendedSafeScope" to recommendedSafeScope,
                "safeScopePolicy" to normalizedSafeScopePolicy,
                "appliedSafeScope" to appliedSafeScope,
                "ancestorsTotal" to ancestorsTotal,
                "ancestorsStatusCounts" to ancestorsStatusCounts,
                "descendantsTotal" to descendantsTotal,
                "descendantsStatusCounts" to descendantsStatusCounts,
                "ancestorsTruncated" to ancestorsTruncated,
                "descendantsTruncated" to descendantsTruncated,
                "nextRecommendedDepth" to nextRecommendedDepth
            )
        ).build()
    }
}
