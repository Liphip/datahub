package com.dataturntable.api

import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertChannelType
import com.dataturntable.alert.AlertRule
import com.dataturntable.alert.AlertHistory
import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleVersion
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
import io.quarkus.security.Authenticated
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class AlertResource {
    
    @GET
    @Path("/channels")
    fun listChannels(@QueryParam("tenantId") tenantId: String): Response {
        val channels = AlertChannel.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(channels.map { mapOf("id" to it.id, "name" to it.name, "channelType" to it.channelType?.name) }).build()
    }
    
    @POST
    @Path("/channels")
    @Transactional
    fun createChannel(@QueryParam("tenantId") tenantId: String, request: Map<String, Any>): Response {
        val channel = AlertChannel().apply {
            name = request["name"] as? String
            channelType = (request["channelType"] as? String)?.let { AlertChannelType.valueOf(it.uppercase()) }
            config = request["config"]?.toString() ?: "{}"
        }
        channel.persist()
        return Response.status(201).entity(mapOf("id" to channel.id)).build()
    }
    
    @GET
    @Path("/rules")
    fun listRules(@QueryParam("tenantId") tenantId: String): Response {
        val rules = AlertRule.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(rules.map { mapOf("id" to it.id, "name" to it.name, "enabled" to it.enabled) }).build()
    }
    
    @POST
    @Path("/rules")
    @Transactional
    fun createRule(@QueryParam("tenantId") tenantId: String, request: Map<String, Any>): Response {
        val rule = AlertRule().apply {
            name = request["name"] as? String
            condition = request["condition"]?.toString() ?: "{}"
        }
        rule.persist()
        return Response.status(201).entity(mapOf("id" to rule.id)).build()
    }
    
    @GET
    @Path("/history")
    fun getHistory(@QueryParam("tenantId") tenantId: String, @QueryParam("limit") limit: Int?): Response {
        val history = AlertHistory.findRecentByTenant(UUID.fromString(tenantId), limit ?: 100)
        return Response.ok(history.map { mapOf("id" to it.id, "title" to it.title, "status" to it.status.name) }).build()
    }
}
