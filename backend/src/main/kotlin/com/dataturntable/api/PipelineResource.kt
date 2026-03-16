package com.dataturntable.api

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.TriggerType
import io.quarkus.security.Authenticated
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
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String): Response {
        val pipelines = Pipeline.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(pipelines.map { mapOf("id" to it.id, "name" to it.name, "isActive" to it.isActive) }).build()
    }
    
    @POST
    @Transactional
    fun create(request: Map<String, Any>): Response {
        val tenantId = request["tenantId"] as? String
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        
        val tenant = com.dataturntable.tenant.Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val pipeline = Pipeline().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.kafkaConsumerGroup = "pipeline-${java.util.UUID.randomUUID()}"
            this.isActive = false
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        pipeline.persist()
        
        return Response.status(201).entity(mapOf("id" to pipeline.id, "name" to pipeline.name)).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val pipeline = Pipeline.findById(UUID.fromString(id))
        return if (pipeline != null) {
            Response.ok(mapOf("id" to pipeline.id, "name" to pipeline.name, "isActive" to pipeline.isActive)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val pipeline = Pipeline.findById(UUID.fromString(id))
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
        val pipeline = Pipeline.findById(UUID.fromString(id))
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
            pipeline.updatedAt = java.time.Instant.now()
            pipeline.persist()
            Response.ok(mapOf("id" to pipeline.id, "name" to pipeline.name, "isActive" to pipeline.isActive)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @POST
    @Path("/{id}/run")
    fun trigger(@PathParam("id") id: String, @QueryParam("triggerType") triggerType: String?): Response {
        val pipeline = Pipeline.findById(UUID.fromString(id))
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
