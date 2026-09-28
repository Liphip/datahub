package com.dataturntable.api

import com.dataturntable.audit.AuditService
import com.dataturntable.auth.ApiKey
import com.dataturntable.auth.User
import com.dataturntable.connectors.Connector
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.security.AbilityEvaluator
import com.dataturntable.security.IngestAuthorizationService
import com.dataturntable.security.SecurityUtils
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Ingest API keys: credentials for devices and systems that push data (`X-API-Key` on the ingest
 * API and WebSocket). A key is granted exact pipelines and connectors (`ingest:pipeline:<id>`,
 * `ingest:connector:<id>`), is shown once when created and stored only as its SHA-256.
 * Managing keys needs `secret:manage`.
 */
@Path("/api/internal/v1/api-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class ApiKeyResource {
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var em: EntityManager
    @Inject lateinit var audit: AuditService
    @Inject lateinit var ingestAuthorization: IngestAuthorizationService

    private val mapper = ObjectMapper()
    private val random = SecureRandom()

    private fun currentUser(): User? = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.let { em.find(User::class.java, it) }

    private fun error(status: Int, message: String) = Response.status(status).entity(mapOf("error" to message)).build()

    private fun authorize(tenantId: UUID): Response? {
        val user = currentUser() ?: return error(401, "Unauthorized")
        if (!SecurityUtils.canAccessTenant(user, tenantId.toString())) return error(403, "Forbidden")
        if (!SecurityUtils.hasAbility(user, "secret:manage")) return error(403, "Insufficient permissions")
        return null
    }

    private fun dto(key: ApiKey): Map<String, Any?> {
        val grants = AbilityEvaluator.parse(key.abilities)
        fun ids(kind: String) = grants.filter { it.startsWith("ingest:$kind:") }.map { it.removePrefix("ingest:$kind:") }
        return mapOf(
            "id" to key.id, "name" to key.name, "pipelineIds" to ids("pipeline"), "connectorIds" to ids("connector"),
            "expiresAt" to key.expiresAt, "lastUsedAt" to key.lastUsedAt, "isActive" to key.isActive,
        )
    }

    @GET
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val tid = runCatching { UUID.fromString(tenantId) }.getOrNull() ?: return error(400, "Valid tenantId is required")
        authorize(tid)?.let { return it }
        return Response.ok(em.createQuery("SELECT k FROM ApiKey k WHERE k.tenant.id = :t ORDER BY k.name", ApiKey::class.java)
            .setParameter("t", tid).resultList.map(::dto)).build()
    }

    /** Body: `{"name", "pipelineIds": [...], "connectorIds": [...], "expiresAt"?}`; the response contains `key` once. */
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantId: String?, body: Map<String, Any?>): Response {
        val tid = runCatching { UUID.fromString(tenantId) }.getOrNull() ?: return error(400, "Valid tenantId is required")
        authorize(tid)?.let { return it }
        val name = body["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return error(400, "name is required")
        fun uuids(field: String): List<UUID?> = (body[field] as? List<*>).orEmpty().map { runCatching { UUID.fromString(it.toString()) }.getOrNull() }
        val pipelineIds = uuids("pipelineIds").map { it ?: return error(400, "pipelineIds must contain ids") }
        val connectorIds = uuids("connectorIds").map { it ?: return error(400, "connectorIds must contain ids") }
        if (pipelineIds.isEmpty() && connectorIds.isEmpty()) return error(400, "Grant at least one pipeline or connector")
        pipelineIds.forEach { id ->
            if (em.find(Pipeline::class.java, id)?.tenant?.id != tid) return error(400, "Pipeline $id not found in this tenant")
        }
        connectorIds.forEach { id ->
            val connector = em.find(Connector::class.java, id)
            if (connector?.tenant?.id != tid || connector.direction != Connector.Direction.INPUT) return error(400, "Input connector $id not found in this tenant")
        }
        val expiresAt = body["expiresAt"]?.toString()?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrNull() ?: return error(400, "expiresAt must be an ISO-8601 instant")
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) return error(400, "expiresAt must be in the future")
        val exists = em.createQuery("SELECT COUNT(k) FROM ApiKey k WHERE k.tenant.id = :t AND k.name = :n", java.lang.Long::class.java)
            .setParameter("t", tid).setParameter("n", name).singleResult.toLong() > 0
        if (exists) return error(409, "An API key named '$name' exists")

        val raw = "dt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val grants = pipelineIds.map { "ingest:pipeline:$it" } + connectorIds.map { "ingest:connector:$it" }
        val key = ApiKey().apply {
            tenant = em.find(Tenant::class.java, tid)
            this.name = name
            keyHash = IngestAuthorizationService.sha256(raw)
            abilities = mapper.writeValueAsString(grants)
            this.expiresAt = expiresAt
        }
        em.persist(key)
        em.flush()
        audit.record(currentUser()?.id, tid, "apikey.created", "api_key", key.id.toString(), mapOf("name" to name, "grants" to grants, "expiresAt" to expiresAt))
        return Response.status(201).entity(dto(key) + ("key" to raw)).build()
    }

    /** Revokes a key; ingest refuses it from the next request on this instance and within 30 s on the others. */
    @DELETE
    @Path("/{id}")
    @Transactional
    fun revoke(@PathParam("id") id: String): Response {
        val key = runCatching { UUID.fromString(id) }.getOrNull()?.let { em.find(ApiKey::class.java, it) } ?: return error(404, "API key not found")
        authorize(key.tenant!!.id!!)?.let { return it }
        key.isActive = false
        em.flush()
        audit.record(currentUser()?.id, key.tenant?.id, "apikey.revoked", "api_key", key.id.toString(), mapOf("name" to key.name))
        ingestAuthorization.forget(key.keyHash!!)
        return Response.noContent().build()
    }
}
