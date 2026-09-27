package com.dataturntable.api

import com.dataturntable.alert.AlertChannel
import com.dataturntable.alert.AlertChannelType
import com.dataturntable.alert.AlertHistory
import com.dataturntable.alert.AlertSeverity
import com.dataturntable.alert.AlertDispatcher
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
 * Tenant-scoped management API for alert channels and delivery history.
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

    /** Decodes a persisted JSON value; values are always written as JSON by [serializeJsonValue]. */
    private fun parseJsonValue(raw: String?): Any {
        if (raw.isNullOrBlank()) return emptyMap<String, Any>()
        return objectMapper.readValue(raw, Any::class.java)
    }

    /** Serializes a request value for JSON-backed persistence, using an empty object for `null`. */
    private fun serializeJsonValue(value: Any?): String {
        if (value == null) return "{}"
        return objectMapper.writeValueAsString(value)
    }

    /** Alert channel credentials are represented by secretRefs, never persisted inline. */
    private fun validateChannelConfig(value: Any?): String? {
        val sensitiveNames = setOf("password", "token", "authtoken", "apikey", "authorization", "secret", "clientsecret", "privatekey", "passphrase")
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

    /** Maps an alert delivery record to its public response representation. */
    /**
     * Converts a value into the representation required by its caller.
     */
    private fun toHistoryDto(history: AlertHistory): Map<String, Any?> {
        return mapOf(
            "id" to history.id,
            "tenantId" to history.tenant?.id,
            "channelId" to history.channel?.id,
            "severity" to history.severity?.name,
            "title" to history.title,
            "message" to history.message,
            "payload" to parseJsonValue(history.payload),
            "createdAt" to history.createdAt,
            "sentAt" to history.sentAt,
            "status" to history.status.name,
            "attempts" to history.attempts,
            "nextAttemptAt" to history.nextAttemptAt,
            "lastError" to history.lastError
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
            "SELECT h FROM AlertHistory h WHERE h.tenant.id = :tenantId ORDER BY h.createdAt DESC, h.id DESC",
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
