package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.service.PipelineService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import java.util.UUID

@Path("/pipelines")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class PipelineResource {
    
    @Inject
    lateinit var pipelineService: PipelineService
    
    @GET
    fun getPipelines(@QueryParam("tenantId") tenantId: String?): Response {
        val tenantUuid = tenantId?.let { UUID.fromString(it) }
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        
        val pipelines = pipelineService.getPipelines(tenantUuid)
        return Response.ok(pipelines).build()
    }
    
    @GET
    @Path("/{id}")
    fun getPipeline(@PathParam("id") id: String): Response {
        val pipeline = pipelineService.getPipeline(UUID.fromString(id))
        return if (pipeline != null) {
            Response.ok(pipeline).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @POST
    @Transactional
    fun createPipeline(
        @QueryParam("tenantId") tenantId: String,
        @Valid request: CreatePipelineRequest,
        @Context securityContext: SecurityContext
    ): Response {
        val user = User.findAll().firstResult<User>()
        
        val pipeline = pipelineService.createPipeline(UUID.fromString(tenantId), request, user)
        return Response.status(201).entity(pipeline).build()
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun updatePipeline(
        @PathParam("id") id: String,
        @Valid request: UpdatePipelineRequest
    ): Response {
        val pipeline = pipelineService.updatePipeline(UUID.fromString(id), request)
        return if (pipeline != null) {
            Response.ok(pipeline).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun deletePipeline(@PathParam("id") id: String): Response {
        val deleted = pipelineService.deletePipeline(UUID.fromString(id))
        return if (deleted) {
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @POST
    @Path("/{id}/run")
    @Transactional
    fun triggerPipeline(
        @PathParam("id") id: String,
        @QueryParam("triggerType") triggerType: String?
    ): Response {
        val trigger = triggerType?.let { TriggerType.valueOf(it.uppercase()) } ?: TriggerType.MANUAL
        
        val run = pipelineService.triggerPipeline(UUID.fromString(id), trigger)
        return if (run != null) {
            Response.status(202).entity(run).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Pipeline not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/runs")
    fun getRuns(@PathParam("id") id: String): Response {
        val runs = pipelineService.getRuns(UUID.fromString(id))
        return Response.ok(runs).build()
    }
}
