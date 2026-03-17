package com.dataturntable.api

import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertChannelType
import com.dataturntable.alert.AlertRule
import com.dataturntable.alert.AlertHistory
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
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

    private val objectMapper = ObjectMapper()

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

    private fun parseJsonValue(raw: String?): Any {
        if (raw.isNullOrBlank()) return emptyMap<String, Any>()
        return try {
            objectMapper.readValue(raw, Any::class.java)
        } catch (_: Exception) {
            raw
        }
    }

    private fun serializeJsonValue(value: Any?): String {
        if (value == null) return "{}"
        return try {
            objectMapper.writeValueAsString(value)
        } catch (_: Exception) {
            value.toString()
        }
    }

    private fun toChannelDto(channel: AlertChannel): Map<String, Any?> {
        return mapOf(
            "id" to channel.id,
            "tenantId" to channel.tenant?.id,
            "name" to channel.name,
            "description" to channel.description,
            "channelType" to channel.channelType?.name,
            "config" to parseJsonValue(channel.config),
            "isActive" to channel.isActive,
            "createdAt" to channel.createdAt.toString()
        )
    }

    private fun toRuleDto(rule: AlertRule): Map<String, Any?> {
        return mapOf(
            "id" to rule.id,
            "tenantId" to rule.tenant?.id,
            "pipelineId" to rule.pipeline?.id,
            "channelId" to rule.channel?.id,
            "name" to rule.name,
            "description" to rule.description,
            "condition" to parseJsonValue(rule.condition),
            "enabled" to rule.enabled,
            "cooldownSeconds" to rule.cooldownSeconds,
            "createdAt" to rule.createdAt.toString()
        )
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
        return Response.ok(channels.map { toChannelDto(it) }).build()
    }
    
    @POST
    @Path("/channels")
    @Transactional
    fun createChannel(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
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
            this.description = request["description"] as? String
            this.config = serializeJsonValue(request["config"])
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        channel.persist()
        return Response.status(201).entity(toChannelDto(channel)).build()
    }

    @PATCH
    @Path("/channels/{id}")
    @Transactional
    fun updateChannel(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val channel = em.find(AlertChannel::class.java, parseUuid(id, "channel id"))
            ?: return Response.status(404).entity(mapOf("error" to "Channel not found")).build()

        request["name"]?.let { channel.name = it as String }
        request["description"]?.let { channel.description = it as String }
        request["channelType"]?.let {
            val parsed = parseChannelType(it as String)
                ?: return Response.status(400).entity(mapOf("error" to "Invalid channel type")).build()
            channel.channelType = parsed
        }
        request["config"]?.let {
            channel.config = serializeJsonValue(it)
        }
        request["isActive"]?.let {
            channel.isActive = it as Boolean
        }
        channel.updatedAt = java.time.Instant.now()
        channel.persist()

        return Response.ok(toChannelDto(channel)).build()
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
        return Response.ok(rules.map { toRuleDto(it) }).build()
    }
    
    @POST
    @Path("/rules")
    @Transactional
    fun createRule(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val conditionRaw = request["condition"] ?: return Response.status(400).entity(mapOf("error" to "Condition is required")).build()
        val condition = when (conditionRaw) {
            is String -> conditionRaw
            else -> serializeJsonValue(conditionRaw)
        }
        
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
        return Response.status(201).entity(toRuleDto(rule)).build()
    }

    @PATCH
    @Path("/rules/{id}")
    @Transactional
    fun updateRule(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val rule = em.find(AlertRule::class.java, parseUuid(id, "rule id"))
            ?: return Response.status(404).entity(mapOf("error" to "Rule not found")).build()

        request["name"]?.let { rule.name = it as String }
        request["description"]?.let { rule.description = it as String }
        request["condition"]?.let {
            rule.condition = when (it) {
                is String -> it
                else -> serializeJsonValue(it)
            }
        }
        request["channelId"]?.let {
            val channel = em.find(AlertChannel::class.java, parseUuid(it as String, "channelId"))
                ?: return Response.status(400).entity(mapOf("error" to "Channel not found")).build()
            rule.channel = channel
        }
        request["pipelineId"]?.let {
            if ((it as String).isBlank()) {
                rule.pipeline = null
            } else {
                val pipeline = em.find(Pipeline::class.java, parseUuid(it, "pipelineId"))
                    ?: return Response.status(400).entity(mapOf("error" to "Pipeline not found")).build()
                rule.pipeline = pipeline
            }
        }
        request["enabled"]?.let { rule.enabled = it as Boolean }
        request["cooldownSeconds"]?.let { rule.cooldownSeconds = (it as Number).toInt() }
        rule.updatedAt = java.time.Instant.now()
        rule.persist()

        return Response.ok(toRuleDto(rule)).build()
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
