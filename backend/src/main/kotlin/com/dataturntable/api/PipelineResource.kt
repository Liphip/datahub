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
