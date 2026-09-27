package com.dataturntable.api

import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertChannelType
import com.dataturntable.alert.AlertRule
import com.dataturntable.alert.AlertRuleAction
import com.dataturntable.alert.AlertHistory
import com.dataturntable.alert.AlertSeverity
import com.dataturntable.alert.AlertDispatcher
import com.dataturntable.alert.AlertCooldownService
import com.dataturntable.audit.AuditService
import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.security.SecurityUtils
import com.dataturntable.tenant.Tenant
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

@Path("/api/v1/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
/**
 * Tenant-scoped management API for alert channels, rules, and delivery history.
 *
 * Each handler resolves the JWT subject to a persisted user and verifies both
 * tenant membership and the required alert ability before reading or mutating
 * state. JSON-valued entity fields are decoded for API responses and serialized
 * before persistence so clients can use structured condition/configuration data.
 */
class AlertResource {

    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @Inject
    lateinit var audit: AuditService

    @Inject
    lateinit var cooldowns: AlertCooldownService

    /** Resolves the authenticated JWT subject to its persisted user, or `null` for an invalid subject. */
    /**
     * Implements `currentUser` for this source unit.
     */
    private fun currentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    /** Returns a forbidden response unless [user] can access [tenantId] and has [ability]. */
    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun requireTenantAccess(user: User, tenantId: String, ability: String): Response? {
        if (!SecurityUtils.canAccessTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(user, ability)) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        return null
    }

    /** Converts a case-insensitive external channel type to the persisted enum. */
    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseChannelType(value: String): AlertChannelType? {
        return try {
            AlertChannelType.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Parses a required UUID and reports a client-facing bad-request error naming [field] on failure. */
    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(
                Response.status(400).entity(mapOf("error" to "Invalid $field")).build()
            )
        }
    }

    /** Decodes a persisted JSON value, retaining the original string if legacy/invalid JSON cannot be decoded. */
    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseJsonValue(raw: String?): Any {
        if (raw.isNullOrBlank()) return emptyMap<String, Any>()
        return try {
            objectMapper.readValue(raw, Any::class.java)
        } catch (_: Exception) {
            raw
        }
    }

    /** Serializes a request value for JSON-backed persistence, using an empty object for `null`. */
    /**
     * Converts a value into the representation required by its caller.
     */
    private fun serializeJsonValue(value: Any?): String {
        if (value == null) return "{}"
        return try {
            objectMapper.writeValueAsString(value)
        } catch (_: Exception) {
            value.toString()
        }
    }

    /** Alert channel credentials are represented by secretRefs, never persisted inline. */
    private fun validateChannelConfig(value: Any?): String? {
        val sensitiveNames = setOf("password", "token", "authtoken", "apikey", "authorization", "secret", "clientsecret")
        fun validate(node: Any?, insideSecretRefs: Boolean = false): String? = when (node) {
            is Map<*, *> -> node.entries.firstNotNullOfOrNull { (rawKey, child) ->
                val key = rawKey?.toString()?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase().orEmpty()
                when {
                    key == "secretrefs" -> validate(child, true)
                    !insideSecretRefs && key in sensitiveNames -> "Channel credentials must use secretRefs"
                    else -> validate(child, insideSecretRefs)
                }
            }
            is Collection<*> -> node.firstNotNullOfOrNull { validate(it, insideSecretRefs) }
            else -> null
        }
        return validate(value)
    }

    /** Accepts either a JSON string or a map and returns a condition object suitable for validation. */
    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseConditionMap(raw: Any?): Map<String, Any>? {
        if (raw == null) return null
        return when (raw) {
            is String -> try {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(raw, Map::class.java) as? Map<String, Any>
            } catch (_: Exception) {
                null
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                raw as? Map<String, Any>
            }
            else -> null
        }
    }

    /** Parses a requested rule severity, rejecting unrecognized values rather than silently defaulting. */
    private fun parseRuleSeverity(raw: Any?): AlertSeverity? {
        val name = raw?.toString()?.trim()?.uppercase() ?: return null
        return try {
            AlertSeverity.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Validates recursive `all`/`any` condition groups or a leaf containing non-blank `field` and `operator`. */
    /**
     * Evaluates the named capability or state predicate.
     */
    private fun isValidConditionMap(condition: Map<String, Any>): Boolean {
        if (condition.isEmpty()) return false

        val all = condition["all"] as? List<*>
        if (all != null) {
            if (all.isEmpty()) return false
            return all.all {
                val nested = it as? Map<*, *> ?: return@all false
                @Suppress("UNCHECKED_CAST")
                isValidConditionMap(nested as Map<String, Any>)
            }
        }

        val any = condition["any"] as? List<*>
        if (any != null) {
            if (any.isEmpty()) return false
            return any.all {
                val nested = it as? Map<*, *> ?: return@all false
                @Suppress("UNCHECKED_CAST")
                isValidConditionMap(nested as Map<String, Any>)
            }
        }

        val field = condition["field"] as? String
        val operator = condition["operator"] as? String
        return !field.isNullOrBlank() && !operator.isNullOrBlank()
    }

    /** Maps an alert channel entity to its public response representation. */
    /**
     * Converts a value into the representation required by its caller.
     */
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

    /** Maps an alert rule entity to its public response representation. */
    /**
     * Converts a value into the representation required by its caller.
     */
    private fun toRuleDto(rule: AlertRule): Map<String, Any?> {
        val actions = ruleActions(rule).mapNotNull { action ->
            action.channel?.let { channel ->
                mapOf("id" to action.id, "channelId" to channel.id, "channelName" to channel.name, "channelType" to channel.channelType?.name)
            }
        }
        return mapOf(
            "id" to rule.id,
            "tenantId" to rule.tenant?.id,
            "pipelineId" to rule.pipeline?.id,
            "channelId" to rule.channel?.id,
            "actions" to actions,
            "channelIds" to actions.mapNotNull { it["channelId"] },
            "name" to rule.name,
            "description" to rule.description,
            "condition" to parseJsonValue(rule.condition),
            "severity" to rule.severity.name,
            "message" to rule.message,
            "enabled" to rule.enabled,
            "cooldownSeconds" to rule.cooldownSeconds,
            "createdAt" to rule.createdAt.toString()
        )
    }

    /** Reads normalized actions; old rules retain their single legacy channel as an implicit action. */
    private fun ruleActions(rule: AlertRule): List<AlertRuleAction> {
        val ruleId = rule.id ?: return emptyList()
        val persisted = em.createQuery(
            "SELECT a FROM AlertRuleAction a JOIN FETCH a.channel WHERE a.rule.id = :ruleId ORDER BY a.createdAt ASC",
            AlertRuleAction::class.java
        ).setParameter("ruleId", ruleId).resultList
        if (persisted.isNotEmpty() || rule.channel == null) return persisted
        return listOf(AlertRuleAction().apply { this.rule = rule; this.channel = rule.channel })
    }

    /** Parses the optional normalized action channel list without conflating absent and empty input. */
    private fun actionChannelIds(request: Map<String, Any>): List<String>? {
        val raw = request["channelIds"] ?: return null
        val values = raw as? List<*> ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "channelIds must be an array")).build())
        return values.map { it?.toString()?.trim().orEmpty() }
    }

    private fun resolveActionChannels(channelIds: List<String>, tenant: Tenant): List<AlertChannel> {
        if (channelIds.any { it.isBlank() }) throw WebApplicationException(Response.status(400).entity(mapOf("error" to "channelIds must contain valid IDs")).build())
        val ids = channelIds.distinct()
        return ids.map { channelId ->
            val channel = em.find(AlertChannel::class.java, parseUuid(channelId, "channelId"))
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Channel not found")).build())
            if (channel.tenant?.id != tenant.id) throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Channel does not belong to tenant")).build())
            channel
        }
    }

    private fun replaceActions(rule: AlertRule, channels: List<AlertChannel>) {
        val ruleId = rule.id ?: return
        em.createQuery("DELETE FROM AlertRuleAction a WHERE a.rule.id = :ruleId").setParameter("ruleId", ruleId).executeUpdate()
        channels.forEach { channel -> em.persist(AlertRuleAction().apply { this.rule = rule; this.channel = channel }) }
        // Retain the first channel as a legacy-compatible summary for callers that have not adopted actions.
        rule.channel = channels.firstOrNull()
    }

    /** Maps an alert delivery record to its public response representation. */
    /**
     * Converts a value into the representation required by its caller.
     */
    private fun toHistoryDto(history: AlertHistory): Map<String, Any?> {
        return mapOf(
            "id" to history.id,
            "tenantId" to history.tenant?.id,
            "ruleId" to history.rule?.id,
            "channelId" to history.channel?.id,
            "severity" to history.severity?.name,
            "title" to history.title,
            "message" to history.message,
            "payload" to parseJsonValue(history.payload),
            "sentAt" to history.sentAt,
            "status" to history.status.name
        )
    }
    
    @GET
    @Path("/channels")
    /** Lists all alert channels in a tenant after enforcing `alert:read`. */
    /**
     * Retrieves or enumerates the requested state.
     */
    fun listChannels(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("limit") limit: Int?,
        @QueryParam("page") page: Int?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseUuid(tenantId, "tenantId")
        requireTenantAccess(currentUser, tenantUuid.toString(), "alert:read")?.let { return it }
        val effectiveLimit = limit ?: 50
        val effectivePage = page ?: 0
        if (effectiveLimit !in 1..200 || effectivePage < 0) {
            return Response.status(400).entity(mapOf("error" to "limit must be between 1 and 200 and page must be non-negative")).build()
        }
        val channels = em.createQuery(
            "SELECT c FROM AlertChannel c WHERE c.tenant.id = :tenantId ORDER BY c.createdAt DESC, c.id DESC",
            AlertChannel::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setFirstResult(effectivePage * effectiveLimit)
            .setMaxResults(effectiveLimit + 1)
            .resultList
        return Response.ok(mapOf(
            "items" to channels.take(effectiveLimit).map { toChannelDto(it) },
            "page" to effectivePage,
            "limit" to effectiveLimit,
            "hasMore" to (channels.size > effectiveLimit)
        )).build()
    }
    
    @POST
    @Path("/channels")
    @Transactional
    /** Creates an active alert channel in the requested tenant after enforcing `alert:configure`. */
    /**
     * Creates or persists the requested state.
     */
    fun createChannel(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        val tenantUuid = parseUuid(tenantId, "tenantId")
        requireTenantAccess(currentUser, tenantUuid.toString(), "alert:configure")?.let { return it }
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val channelType = request["channelType"] as? String ?: return Response.status(400).entity(mapOf("error" to "Channel type is required")).build()
        val parsedChannelType = parseChannelType(channelType)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid channel type")).build()
        validateChannelConfig(request["config"])?.let { return Response.status(400).entity(mapOf("error" to it)).build() }
        
        val tenant = em.find(Tenant::class.java, tenantUuid)
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
        audit.record(currentUser.id, tenantUuid, "alert.channel.created", "alert_channel", channel.id.toString(),
            mapOf("name" to channel.name, "channelType" to parsedChannelType.name))
        return Response.status(201).entity(toChannelDto(channel)).build()
    }

    @PATCH
    @Path("/channels/{id}")
    @Transactional
    /** Applies supplied channel fields and records a new update timestamp. */
    /**
     * Updates the requested state.
     */
    fun updateChannel(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val channel = em.find(AlertChannel::class.java, parseUuid(id, "channel id"))
            ?: return Response.status(404).entity(mapOf("error" to "Channel not found")).build()
        val tenantId = channel.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Channel tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "alert:configure")?.let { return it }

        request["name"]?.let { channel.name = it as String }
        request["description"]?.let { channel.description = it as String }
        request["channelType"]?.let {
            val parsed = parseChannelType(it as String)
                ?: return Response.status(400).entity(mapOf("error" to "Invalid channel type")).build()
            channel.channelType = parsed
        }
        request["config"]?.let {
            validateChannelConfig(it)?.let { error -> return Response.status(400).entity(mapOf("error" to error)).build() }
            channel.config = serializeJsonValue(it)
        }
        request["isActive"]?.let {
            channel.isActive = it as Boolean
        }
        channel.updatedAt = java.time.Instant.now()
        channel.persist()
        audit.record(currentUser.id, channel.tenant?.id, "alert.channel.updated", "alert_channel", channel.id.toString(),
            mapOf("name" to channel.name, "channelType" to channel.channelType?.name, "active" to channel.isActive))

        return Response.ok(toChannelDto(channel)).build()
    }
    
    @DELETE
    @Path("/channels/{id}")
    @Transactional
    /** Deletes one configured channel after tenant and `alert:configure` authorization. */
    /**
     * Removes, stops, or releases the requested state.
     */
    fun deleteChannel(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val channel = em.find(AlertChannel::class.java, parseUuid(id, "channel id"))
        return if (channel != null) {
            val tenantId = channel.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Channel tenant not found")).build()
            requireTenantAccess(currentUser, tenantId, "alert:configure")?.let { return it }
            audit.record(currentUser.id, channel.tenant?.id, "alert.channel.deleted", "alert_channel", channel.id.toString(),
                mapOf("name" to channel.name, "channelType" to channel.channelType?.name))
            channel.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Channel not found")).build()
        }
    }
    
    @GET
    @Path("/rules")
    /** Lists a tenant's alert rules after enforcing `alert:read`. */
    /**
     * Retrieves or enumerates the requested state.
     */
    fun listRules(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("limit") limit: Int?,
        @QueryParam("page") page: Int?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseUuid(tenantId, "tenantId")
        requireTenantAccess(currentUser, tenantUuid.toString(), "alert:read")?.let { return it }
        val effectiveLimit = limit ?: 50
        val effectivePage = page ?: 0
        if (effectiveLimit !in 1..200 || effectivePage < 0) {
            return Response.status(400).entity(mapOf("error" to "limit must be between 1 and 200 and page must be non-negative")).build()
        }
        val rules = em.createQuery(
            "SELECT r FROM AlertRule r WHERE r.tenant.id = :tenantId ORDER BY r.createdAt DESC, r.id DESC",
            AlertRule::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setFirstResult(effectivePage * effectiveLimit)
            .setMaxResults(effectiveLimit + 1)
            .resultList
        return Response.ok(mapOf(
            "items" to rules.take(effectiveLimit).map { toRuleDto(it) },
            "page" to effectivePage,
            "limit" to effectiveLimit,
            "hasMore" to (rules.size > effectiveLimit)
        )).build()
    }
    
    @POST
    @Path("/rules")
    @Transactional
    /** Creates a rule with a valid condition tree and references to existing pipeline/channel entities. */
    /**
     * Creates or persists the requested state.
     */
    fun createRule(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        val tenantUuid = parseUuid(tenantId, "tenantId")
        requireTenantAccess(currentUser, tenantUuid.toString(), "alert:configure")?.let { return it }
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val conditionRaw = request["condition"] ?: return Response.status(400).entity(mapOf("error" to "Condition is required")).build()
        val conditionMap = parseConditionMap(conditionRaw)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid condition JSON")).build()
        if (!isValidConditionMap(conditionMap)) {
            return Response.status(400).entity(mapOf("error" to "Invalid condition format")).build()
        }
        val condition = serializeJsonValue(conditionMap)
        val severity = if (request.containsKey("severity")) {
            parseRuleSeverity(request["severity"])
                ?: return Response.status(400).entity(mapOf("error" to "Invalid severity")).build()
        } else {
            AlertSeverity.WARNING
        }

        val tenant = em.find(Tenant::class.java, tenantUuid)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val actionIds = actionChannelIds(request)
        val channelId = request["channelId"] as? String
        val channel = channelId?.let { em.find(AlertChannel::class.java, parseUuid(it, "channelId")) }
        if (channelId != null && channel == null) {
            return Response.status(400).entity(mapOf("error" to "Channel not found")).build()
        }
        if (channel != null && channel.tenant?.id != tenant.id) {
            return Response.status(400).entity(mapOf("error" to "Channel does not belong to tenant")).build()
        }
        val actionChannels = actionIds?.let { resolveActionChannels(it, tenant) } ?: listOfNotNull(channel)
        
        val pipelineId = request["pipelineId"] as? String
        val pipeline = pipelineId?.let { em.find(Pipeline::class.java, parseUuid(it, "pipelineId")) }
        if (pipelineId != null && pipeline == null) {
            return Response.status(400).entity(mapOf("error" to "Pipeline not found")).build()
        }
        if (pipeline != null && pipeline.tenant?.id != tenant.id) {
            return Response.status(400).entity(mapOf("error" to "Pipeline does not belong to tenant")).build()
        }
        
        val rule = AlertRule().apply {
            this.tenant = tenant
            this.name = name
            this.description = request["description"] as? String
            this.condition = condition
            this.severity = severity
            this.message = request["message"] as? String
            this.channel = actionChannels.firstOrNull()
            this.pipeline = pipeline
            this.enabled = true
            this.cooldownSeconds = (request["cooldownSeconds"] as? Int) ?: 60
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        rule.persist()
        cooldowns.initialize(rule.id)
        replaceActions(rule, actionChannels)
        audit.record(currentUser.id, tenantUuid, "alert.rule.created", "alert_rule", rule.id.toString(),
            mapOf("name" to rule.name, "enabled" to rule.enabled, "channelId" to rule.channel?.id, "actionCount" to actionChannels.size, "pipelineId" to rule.pipeline?.id))
        return Response.status(201).entity(toRuleDto(rule)).build()
    }

    @PATCH
    @Path("/rules/{id}")
    @Transactional
    /** Updates a rule's supplied fields, validating any replacement condition or related entity reference. */
    /**
     * Updates the requested state.
     */
    fun updateRule(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val rule = em.find(AlertRule::class.java, parseUuid(id, "rule id"))
            ?: return Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        val tenantId = rule.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Rule tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "alert:configure")?.let { return it }

        request["name"]?.let { rule.name = it as String }
        request["description"]?.let { rule.description = it as String }
        request["condition"]?.let {
            val conditionMap = parseConditionMap(it)
                ?: return Response.status(400).entity(mapOf("error" to "Invalid condition JSON")).build()
            if (!isValidConditionMap(conditionMap)) {
                return Response.status(400).entity(mapOf("error" to "Invalid condition format")).build()
            }
            rule.condition = serializeJsonValue(conditionMap)
        }
        request["severity"]?.let {
            rule.severity = parseRuleSeverity(it) ?: return Response.status(400).entity(mapOf("error" to "Invalid severity")).build()
        }
        request["message"]?.let { rule.message = it as? String }
        request["channelId"]?.let {
            val channel = em.find(AlertChannel::class.java, parseUuid(it as String, "channelId"))
                ?: return Response.status(400).entity(mapOf("error" to "Channel not found")).build()
            if (channel.tenant?.id != rule.tenant?.id) {
                return Response.status(400).entity(mapOf("error" to "Channel does not belong to tenant")).build()
            }
            rule.channel = channel
            replaceActions(rule, listOf(channel))
        }
        actionChannelIds(request)?.let { actionIds ->
            replaceActions(rule, resolveActionChannels(actionIds, rule.tenant
                ?: return Response.status(400).entity(mapOf("error" to "Rule tenant not found")).build()))
        }
        request["pipelineId"]?.let {
            if ((it as String).isBlank()) {
                rule.pipeline = null
            } else {
                val pipeline = em.find(Pipeline::class.java, parseUuid(it, "pipelineId"))
                    ?: return Response.status(400).entity(mapOf("error" to "Pipeline not found")).build()
                if (pipeline.tenant?.id != rule.tenant?.id) {
                    return Response.status(400).entity(mapOf("error" to "Pipeline does not belong to tenant")).build()
                }
                rule.pipeline = pipeline
            }
        }
        request["enabled"]?.let { rule.enabled = it as Boolean }
        request["cooldownSeconds"]?.let { rule.cooldownSeconds = (it as Number).toInt() }
        rule.updatedAt = java.time.Instant.now()
        rule.persist()
        audit.record(currentUser.id, rule.tenant?.id, "alert.rule.updated", "alert_rule", rule.id.toString(),
            mapOf("name" to rule.name, "enabled" to rule.enabled, "channelId" to rule.channel?.id, "actionCount" to ruleActions(rule).size, "pipelineId" to rule.pipeline?.id))

        return Response.ok(toRuleDto(rule)).build()
    }
    
    @DELETE
    @Path("/rules/{id}")
    @Transactional
    /** Deletes one alert rule after tenant and `alert:configure` authorization. */
    /**
     * Removes, stops, or releases the requested state.
     */
    fun deleteRule(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val rule = em.find(AlertRule::class.java, parseUuid(id, "rule id"))
        return if (rule != null) {
            val tenantId = rule.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Rule tenant not found")).build()
            requireTenantAccess(currentUser, tenantId, "alert:configure")?.let { return it }
            audit.record(currentUser.id, rule.tenant?.id, "alert.rule.deleted", "alert_rule", rule.id.toString(),
                mapOf("name" to rule.name, "channelId" to rule.channel?.id, "pipelineId" to rule.pipeline?.id))
            rule.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        }
    }

    /** Evaluates a rule against supplied data without sending an action or creating history. */
    @POST
    @Path("/rules/{id}/test")
    fun testRule(@PathParam("id") id: String, request: Map<String, Any?>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val rule = em.find(AlertRule::class.java, parseUuid(id, "rule id"))
            ?: return Response.status(404).entity(mapOf("error" to "Rule not found")).build()
        val tenantId = rule.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Rule tenant not found")).build()
        requireTenantAccess(currentUser, tenantId, "alert:configure")?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val payload = request["payload"] as? Map<String, Any?> ?: emptyMap()
        val matched = alertDispatcher.matchesCondition(rule.condition, payload.filterValues { it != null }.mapValues { it.value!! })
        val actions = ruleActions(rule).mapNotNull { action -> action.channel?.let { channel ->
            mapOf("channelId" to channel.id, "channelType" to channel.channelType?.name, "channelName" to channel.name)
        } }
        return Response.ok(mapOf(
            "ruleId" to rule.id,
            "matched" to matched,
            "wouldDispatch" to false,
            "actions" to if (matched) actions else emptyList<Any>(),
            "payload" to payload
        )).build()
    }
    
    @GET
    @Path("/history")
    /** Returns a bounded, tenant-authorized recent alert delivery history. */
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getHistory(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("limit") limit: Int?,
        @QueryParam("page") page: Int?
    ): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = parseUuid(tenantId, "tenantId")
        requireTenantAccess(currentUser, tenantUuid.toString(), "alert:read")?.let { return it }
        val pageSize = limit ?: 50
        val pageIndex = page ?: 0
        if (pageSize !in 1..200 || pageIndex < 0) {
            return Response.status(400).entity(mapOf("error" to "limit must be 1..200 and page must be non-negative")).build()
        }
        val history = em.createQuery(
            "SELECT h FROM AlertHistory h WHERE h.tenant.id = :tenantId ORDER BY h.sentAt DESC, h.id DESC",
            AlertHistory::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setFirstResult(pageIndex * pageSize)
            .setMaxResults(pageSize + 1)
            .resultList
        val hasMore = history.size > pageSize
        return Response.ok(mapOf(
            "items" to history.take(pageSize).map { toHistoryDto(it) },
            "page" to pageIndex,
            "limit" to pageSize,
            "hasMore" to hasMore
        )).build()
    }
}
