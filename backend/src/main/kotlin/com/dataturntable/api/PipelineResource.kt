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
    fun list(@QueryParam("tenantId") tenantId: String, @QueryParam("includeRunSummary") includeRunSummary: String?): Response {
        val pipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId",
            Pipeline::class.java
        )
            .setParameter("tenantId", UUID.fromString(tenantId))
            .resultList
        val withRunSummary = includeRunSummary.equals("true", ignoreCase = true)
        return Response.ok(
            pipelines.map {
                val base = pipelineToMap(it)
                if (!withRunSummary || it.id == null) {
                    base
                } else {
                    base + latestRunSummary(it.id!!)
                }
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
        val pipeline = em.find(Pipeline::class.java, UUID.fromString(id))
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
        val pipeline = em.find(Pipeline::class.java, UUID.fromString(id))
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
        val pipeline = em.find(Pipeline::class.java, UUID.fromString(id))
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
        val pipeline = em.find(Pipeline::class.java, UUID.fromString(id))
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
