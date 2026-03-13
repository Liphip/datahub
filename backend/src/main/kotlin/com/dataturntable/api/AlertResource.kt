package com.dataturntable.api

import com.dataturntable.alert.*
import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class AlertResource {
    
    private val objectMapper = ObjectMapper()
    
    @GET
    @Path("/channels")
    fun getChannels(@QueryParam("tenantId") tenantId: String?): Response {
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }
        
        val channels = AlertChannel.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(channels.map { toChannelDto(it) }).build()
    }
    
    @GET
    @Path("/channels/{id}")
    fun getChannel(@PathParam("id") id: String): Response {
        val channel = AlertChannel.findById(UUID.fromString(id))
        return if (channel != null) {
            Response.ok(toChannelDto(channel)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Channel not found")).build()
        }
    }
    
    @POST
    @Path("/channels")
    @Transactional
    fun createChannel(
        @QueryParam("tenantId") tenantId: String,
        @Valid request: CreateAlertChannelRequest
    ): Response {
        val tenant = Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val channel = AlertChannel().apply {
            this.tenant = tenant
            this.name = request.name
            this.description = request.description
            this.channelType = AlertChannelType.valueOf(request.channelType.uppercase())
            this.config = objectMapper.writeValueAsString(request.config)
            this.createdBy = User.findAll().firstResult()
        }
        
        channel.persist()
        return Response.status(201).entity(toChannelDto(channel)).build()
    }
    
    @PATCH
    @Path("/channels/{id}")
    @Transactional
    fun updateChannel(
        @PathParam("id") id: String,
        @Valid request: UpdateAlertChannelRequest
    ): Response {
        val channel = AlertChannel.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Channel not found")).build()
        
        request.name?.let { channel.name = it }
        request.description?.let { channel.description = it }
        request.config?.let { channel.config = objectMapper.writeValueAsString(it) }
        request.isActive?.let { channel.isActive = it }
        
        channel.updatedAt = java.time.Instant.now()
        channel.persist()
        
        return Response.ok(toChannelDto(channel)).build()
    }
    
    @DELETE
    @Path("/channels/{id}")
    @Transactional
    fun deleteChannel(@PathParam("id") id: String): Response {
        val channel = AlertChannel.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Channel not found")).build()
        
        channel.delete()
        return Response.noContent().build()
    }
    
    @GET
    @Path("/rules")
    fun getRules(@QueryParam("tenantId") tenantId: String?): Response {
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }
        
        val rules = AlertRule.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(rules.map { toRuleDto(it) }).build()
    }
    
    @GET
    @Path("/rules/{id}")
    fun getRule(@PathParam("id") id: String): Response {
        val rule = AlertRule.findById(UUID.fromString(id))
        return if (rule != null) {
            Response.ok(toRuleDto(rule)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        }
    }
    
    @POST
    @Path("/rules")
    @Transactional
    fun createRule(
        @QueryParam("tenantId") tenantId: String,
        @Valid request: CreateAlertRuleRequest
    ): Response {
        val tenant = Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val rule = AlertRule().apply {
            this.tenant = tenant
            this.name = request.name
            this.description = request.description
            this.condition = objectMapper.writeValueAsString(request.condition)
            this.channel = request.channelId?.let { AlertChannel.findById(UUID.fromString(it)) }
            this.pipeline = request.pipelineId?.let { Pipeline.findById(UUID.fromString(it)) }
            this.enabled = request.enabled ?: true
            this.cooldownSeconds = request.cooldownSeconds ?: 60
            this.createdBy = User.findAll().firstResult()
        }
        
        rule.persist()
        return Response.status(201).entity(toRuleDto(rule)).build()
    }
    
    @PATCH
    @Path("/rules/{id}")
    @Transactional
    fun updateRule(
        @PathParam("id") id: String,
        @Valid request: UpdateAlertRuleRequest
    ): Response {
        val rule = AlertRule.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        
        request.name?.let { rule.name = it }
        request.description?.let { rule.description = it }
        request.condition?.let { rule.condition = objectMapper.writeValueAsString(it) }
        request.enabled?.let { rule.enabled = it }
        request.cooldownSeconds?.let { rule.cooldownSeconds = it }
        
        rule.updatedAt = java.time.Instant.now()
        rule.persist()
        
        return Response.ok(toRuleDto(rule)).build()
    }
    
    @DELETE
    @Path("/rules/{id}")
    @Transactional
    fun deleteRule(@PathParam("id") id: String): Response {
        val rule = AlertRule.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        
        rule.delete()
        return Response.noContent().build()
    }
    
    @GET
    @Path("/history")
    fun getHistory(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("limit") limit: Int? = 100
    ): Response {
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }
        
        val history = AlertHistory.findRecentByTenant(UUID.fromString(tenantId), limit ?: 100)
        return Response.ok(history.map { toHistoryDto(it) }).build()
    }
    
    private fun toChannelDto(channel: AlertChannel): AlertChannelDto {
        return AlertChannelDto(
            id = channel.id.toString(),
            tenantId = channel.tenant?.id.toString(),
            name = channel.name ?: "",
            description = channel.description,
            channelType = channel.channelType?.name ?: "WEBHOOK",
            config = parseConfig(channel.config),
            isActive = channel.isActive,
            createdAt = channel.createdAt
        )
    }
    
    private fun toRuleDto(rule: AlertRule): AlertRuleDto {
        return AlertRuleDto(
            id = rule.id.toString(),
            tenantId = rule.tenant?.id.toString(),
            pipelineId = rule.pipeline?.id.toString(),
            channelId = rule.channel?.id.toString(),
            name = rule.name ?: "",
            description = rule.description,
            condition = parseConfig(rule.condition),
            enabled = rule.enabled,
            cooldownSeconds = rule.cooldownSeconds,
            createdAt = rule.createdAt
        )
    }
    
    private fun toHistoryDto(history: AlertHistory): AlertHistoryDto {
        return AlertHistoryDto(
            id = history.id.toString(),
            tenantId = history.tenant?.id.toString(),
            ruleId = history.rule?.id.toString(),
            channelId = history.channel?.id.toString(),
            severity = history.severity?.name ?: "INFO",
            title = history.title ?: "",
            message = history.message,
            payload = parseConfig(history.payload),
            sentAt = history.sentAt,
            status = history.status?.name ?: "PENDING"
        )
    }
    
    private fun parseConfig(config: String?): Map<String, Any> {
        if (config.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(config, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
    }
}

data class AlertChannelDto(
    val id: String?,
    val tenantId: String?,
    val name: String,
    val description: String?,
    val channelType: String,
    val config: Map<String, Any>,
    val isActive: Boolean,
    val createdAt: java.time.Instant?
)

data class CreateAlertChannelRequest(
    val name: String,
    val description: String? = null,
    val channelType: String,
    val config: Map<String, Any> = emptyMap()
)

data class UpdateAlertChannelRequest(
    val name: String? = null,
    val description: String? = null,
    val config: Map<String, Any>? = null,
    val isActive: Boolean? = null
)

data class AlertRuleDto(
    val id: String?,
    val tenantId: String?,
    val pipelineId: String?,
    val channelId: String?,
    val name: String,
    val description: String?,
    val condition: Map<String, Any>,
    val enabled: Boolean,
    val cooldownSeconds: Int,
    val createdAt: java.time.Instant?
)

data class CreateAlertRuleRequest(
    val name: String,
    val description: String? = null,
    val pipelineId: String? = null,
    val channelId: String? = null,
    val condition: Map<String, Any> = emptyMap(),
    val enabled: Boolean = true,
    val cooldownSeconds: Int = 60
)

data class UpdateAlertRuleRequest(
    val name: String? = null,
    val description: String? = null,
    val condition: Map<String, Any>? = null,
    val enabled: Boolean? = null,
    val cooldownSeconds: Int? = null
)

data class AlertHistoryDto(
    val id: String?,
    val tenantId: String?,
    val ruleId: String?,
    val channelId: String?,
    val severity: String,
    val title: String,
    val message: String?,
    val payload: Map<String, Any>?,
    val sentAt: java.time.Instant?,
    val status: String
)
