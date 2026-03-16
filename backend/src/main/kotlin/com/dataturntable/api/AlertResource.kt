package com.dataturntable.api

import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertChannelType
import com.dataturntable.alert.AlertRule
import com.dataturntable.alert.AlertHistory
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import io.quarkus.security.Authenticated
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
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

    @PersistenceContext
    lateinit var em: EntityManager

    private fun parseChannelType(value: String): AlertChannelType? {
        return try {
            AlertChannelType.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(
                Response.status(400).entity(mapOf("error" to "Invalid $field")).build()
            )
        }
    }
    
    @GET
    @Path("/channels")
    fun listChannels(@QueryParam("tenantId") tenantId: String): Response {
        val tenantUuid = parseUuid(tenantId, "tenantId")
        val channels = em.createQuery(
            "SELECT c FROM AlertChannel c WHERE c.tenant.id = :tenantId",
            AlertChannel::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList
        return Response.ok(channels.map { mapOf("id" to it.id, "name" to it.name, "channelType" to it.channelType?.name) }).build()
    }
    
    @POST
    @Path("/channels")
    @Transactional
    fun createChannel(request: Map<String, Any>): Response {
        val tenantId = request["tenantId"] as? String ?: return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val channelType = request["channelType"] as? String ?: return Response.status(400).entity(mapOf("error" to "Channel type is required")).build()
        val parsedChannelType = parseChannelType(channelType)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid channel type")).build()
        
        val tenant = em.find(Tenant::class.java, parseUuid(tenantId, "tenantId"))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val channel = AlertChannel().apply {
            this.name = name
            this.tenant = tenant
            this.channelType = parsedChannelType
            this.config = request["config"]?.toString() ?: "{}"
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        channel.persist()
        return Response.status(201).entity(mapOf("id" to channel.id, "name" to channel.name)).build()
    }
    
    @DELETE
    @Path("/channels/{id}")
    @Transactional
    fun deleteChannel(@PathParam("id") id: String): Response {
        val channel = em.find(AlertChannel::class.java, parseUuid(id, "channel id"))
        return if (channel != null) {
            channel.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Channel not found")).build()
        }
    }
    
    @GET
    @Path("/rules")
    fun listRules(@QueryParam("tenantId") tenantId: String): Response {
        val tenantUuid = parseUuid(tenantId, "tenantId")
        val rules = em.createQuery(
            "SELECT r FROM AlertRule r WHERE r.tenant.id = :tenantId",
            AlertRule::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList
        return Response.ok(rules.map { mapOf("id" to it.id, "name" to it.name, "enabled" to it.enabled) }).build()
    }
    
    @POST
    @Path("/rules")
    @Transactional
    fun createRule(request: Map<String, Any>): Response {
        val tenantId = request["tenantId"] as? String ?: return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val condition = request["condition"] as? String ?: return Response.status(400).entity(mapOf("error" to "Condition is required")).build()
        
        val tenant = em.find(Tenant::class.java, parseUuid(tenantId, "tenantId"))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val channelId = request["channelId"] as? String
        val channel = channelId?.let { em.find(AlertChannel::class.java, parseUuid(it, "channelId")) }
        
        val pipelineId = request["pipelineId"] as? String
        val pipeline = pipelineId?.let { em.find(Pipeline::class.java, parseUuid(it, "pipelineId")) }
        
        val rule = AlertRule().apply {
            this.tenant = tenant
            this.name = name
            this.description = request["description"] as? String
            this.condition = condition
            this.channel = channel
            this.pipeline = pipeline
            this.enabled = true
            this.cooldownSeconds = (request["cooldownSeconds"] as? Int) ?: 60
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        rule.persist()
        return Response.status(201).entity(mapOf("id" to rule.id, "name" to rule.name)).build()
    }
    
    @DELETE
    @Path("/rules/{id}")
    @Transactional
    fun deleteRule(@PathParam("id") id: String): Response {
        val rule = em.find(AlertRule::class.java, parseUuid(id, "rule id"))
        return if (rule != null) {
            rule.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        }
    }
    
    @GET
    @Path("/history")
    fun getHistory(@QueryParam("tenantId") tenantId: String, @QueryParam("limit") limit: Int?): Response {
        val history = AlertHistory.findRecentByTenant(parseUuid(tenantId, "tenantId"), limit ?: 100)
        return Response.ok(history.map { mapOf("id" to it.id, "title" to it.title, "status" to it.status.name) }).build()
    }
}
