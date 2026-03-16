package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.connectors.Connector
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
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

    @PersistenceContext
    lateinit var em: EntityManager

    private fun validateGraphDefinition(tenantId: UUID, graphDefinition: Any?): String? {
        if (graphDefinition !is Map<*, *>) return null
        val nodes = graphDefinition["nodes"] as? List<*> ?: return null
        for (node in nodes) {
            val nodeMap = node as? Map<*, *> ?: continue
            val nodeType = (nodeMap["type"] as? String)?.uppercase() ?: continue
            if (nodeType != "IMPORT" && nodeType != "EXPORT") continue

            val connectorId = nodeMap["connectorId"] as? String
                ?: return "IMPORT and EXPORT nodes require connectorId"
            val connector = try { Connector.findById(UUID.fromString(connectorId)) } catch (_: Exception) { null }
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
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String): Response {
        val pipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId",
            Pipeline::class.java
        )
            .setParameter("tenantId", UUID.fromString(tenantId))
            .resultList
        return Response.ok(pipelines.map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "isActive" to it.isActive,
                "graphDefinition" to it.graphDefinition
            )
        }).build()
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
        
        return Response.status(201).entity(
            mapOf(
                "id" to pipeline.id,
                "name" to pipeline.name,
                "graphDefinition" to pipeline.graphDefinition,
                "isActive" to pipeline.isActive
            )
        ).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val pipeline = em.find(Pipeline::class.java, UUID.fromString(id))
        return if (pipeline != null) {
            Response.ok(
                mapOf(
                    "id" to pipeline.id,
                    "name" to pipeline.name,
                    "isActive" to pipeline.isActive,
                    "graphDefinition" to pipeline.graphDefinition
                )
            ).build()
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
            Response.ok(
                mapOf(
                    "id" to pipeline.id,
                    "name" to pipeline.name,
                    "isActive" to pipeline.isActive,
                    "graphDefinition" to pipeline.graphDefinition
                )
            ).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @POST
    @Path("/{id}/run")
    fun trigger(@PathParam("id") id: String, @QueryParam("triggerType") triggerType: String?): Response {
        val pipeline = em.find(Pipeline::class.java, UUID.fromString(id))
        return if (pipeline != null) {
            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.triggerType = triggerType?.let { TriggerType.valueOf(it.uppercase()) } ?: TriggerType.MANUAL
            }
            run.persist()
            Response.status(201).entity(mapOf("id" to run.id, "status" to run.status.name)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/runs")
    fun getRuns(@PathParam("id") id: String): Response {
        val runs = PipelineRun.findByPipelineId(UUID.fromString(id))
        return Response.ok(runs.map { mapOf("id" to it.id, "status" to it.status.name, "startedAt" to it.startedAt.toString()) }).build()
    }
}
