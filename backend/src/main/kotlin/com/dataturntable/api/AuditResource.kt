package com.dataturntable.api

import com.dataturntable.audit.AuditEvent
import com.dataturntable.audit.AuditService
import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant
import java.util.UUID

/**
 * Read side of the audit trail (AUD-07, AUD-08): search, filter and export `audit_events`.
 *
 * Visibility follows `audit:read`: with a `tenantId` the caller needs that ability and access to
 * the tenant and sees only that tenant's records; without one — installation-wide records,
 * including those that belong to no tenant such as failed logins and retention runs — only a
 * platform administrator may read. Records are never changed here; an export is itself audited.
 */
@Path("/api/internal/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
class AuditResource {
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var em: EntityManager
    @Inject lateinit var audit: AuditService

    private val mapper = com.dataturntable.json.JsonObjectMappers.create()

    private fun currentUser(): User? = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.let { em.find(User::class.java, it) }

    private fun error(status: Int, message: String) = Response.status(status).entity(mapOf("error" to message)).build()

    private class Filter(
        val tenantId: UUID?, val from: Instant?, val to: Instant?, val actorId: UUID?, val action: String?,
        val resourceType: String?, val resourceId: String?, val outcome: String?, val text: String?,
    )

    /** Resolves the caller's scope and parses the filters; a [Response] is the refusal to return. */
    private fun scope(
        tenantId: String?, from: String?, to: String?, actorId: String?, action: String?,
        resourceType: String?, resourceId: String?, outcome: String?, q: String?,
    ): Any {
        val user = currentUser() ?: return error(401, "Unauthorized")
        val tid = tenantId?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: return error(400, "Invalid tenantId")
        }
        if (tid == null) {
            if (!user.isSuperAdmin()) return error(403, "Platform administrator access required without tenantId")
        } else {
            if (!SecurityUtils.canAccessTenant(user, tid.toString())) return error(403, "Forbidden")
            if (!SecurityUtils.hasAbility(user, "audit:read")) return error(403, "Insufficient permissions")
        }
        fun instant(name: String, value: String?): Any? = value?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrNull() ?: return error(400, "Invalid $name, expected an ISO-8601 instant")
        }
        val fromInstant = instant("from", from).also { if (it is Response) return it } as Instant?
        val toInstant = instant("to", to).also { if (it is Response) return it } as Instant?
        val actor = actorId?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: return error(400, "Invalid actorId")
        }
        fun text(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
        return Filter(tid, fromInstant, toInstant, actor, text(action), text(resourceType), text(resourceId), text(outcome), text(q))
    }

    private fun query(filter: Filter, select: String, order: String = ""): jakarta.persistence.Query {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        fun add(clause: String, name: String, value: Any?) { if (value != null) { where += clause; params[name] = value } }
        add("a.tenantId = :tenant", "tenant", filter.tenantId)
        add("a.occurredAt >= :from", "from", filter.from)
        add("a.occurredAt < :to", "to", filter.to)
        add("a.actorId = :actor", "actor", filter.actorId)
        // A trailing * makes the action a prefix filter ("connector.*"), otherwise it is exact.
        filter.action?.let {
            if (it.endsWith("*")) add("a.action LIKE :action ESCAPE '\\'", "action", like(it.dropLast(1)) + "%")
            else add("a.action = :action", "action", it)
        }
        add("a.resourceType = :resourceType", "resourceType", filter.resourceType)
        add("a.resourceId = :resourceId", "resourceId", filter.resourceId)
        add("UPPER(a.outcome) = :outcome", "outcome", filter.outcome?.uppercase())
        filter.text?.let {
            where += "(LOWER(a.action) LIKE :q ESCAPE '\\' OR LOWER(a.resourceType) LIKE :q ESCAPE '\\' " +
                "OR LOWER(COALESCE(a.resourceId, '')) LIKE :q ESCAPE '\\' OR LOWER(COALESCE(a.metadata, '')) LIKE :q ESCAPE '\\')"
            params["q"] = "%" + like(it.lowercase()) + "%"
        }
        val jpql = "SELECT $select FROM AuditEvent a" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + order
        return em.createQuery(jpql).also { q -> params.forEach { (k, v) -> q.setParameter(k, v) } }
    }

    private fun like(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun dto(event: AuditEvent): Map<String, Any?> = mapOf(
        "id" to event.id, "occurredAt" to event.occurredAt, "actorId" to event.actorId, "tenantId" to event.tenantId,
        "action" to event.action, "resourceType" to event.resourceType, "resourceId" to event.resourceId,
        "outcome" to event.outcome, "requestId" to event.requestId,
        "metadata" to event.metadata?.let { runCatching { mapper.readValue(it, Any::class.java) }.getOrDefault(it) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun events(filter: Filter, limit: Int, offset: Int): List<AuditEvent> =
        query(filter, "a", " ORDER BY a.occurredAt DESC, a.id DESC").setFirstResult(offset).setMaxResults(limit).resultList as List<AuditEvent>

    @GET
    fun search(
        @QueryParam("tenantId") tenantId: String?, @QueryParam("from") from: String?, @QueryParam("to") to: String?,
        @QueryParam("actorId") actorId: String?, @QueryParam("action") action: String?,
        @QueryParam("resourceType") resourceType: String?, @QueryParam("resourceId") resourceId: String?,
        @QueryParam("outcome") outcome: String?, @QueryParam("q") q: String?,
        @QueryParam("limit") limit: Int?, @QueryParam("offset") offset: Int?,
    ): Response {
        val filter = scope(tenantId, from, to, actorId, action, resourceType, resourceId, outcome, q)
            .let { it as? Filter ?: return it as Response }
        val pageSize = (limit ?: 50).coerceIn(1, 500)
        val start = (offset ?: 0).coerceAtLeast(0)
        val total = (query(filter, "COUNT(a)").singleResult as Number).toLong()
        return Response.ok(mapOf(
            "total" to total, "limit" to pageSize, "offset" to start,
            "items" to events(filter, pageSize, start).map(::dto),
        )).build()
    }

    /** CSV or JSON download of the filtered trail, newest first, capped at [EXPORT_MAX] records. */
    @GET
    @Path("/export")
    @Produces(MediaType.APPLICATION_JSON, "text/csv")
    fun export(
        @QueryParam("format") format: String?,
        @QueryParam("tenantId") tenantId: String?, @QueryParam("from") from: String?, @QueryParam("to") to: String?,
        @QueryParam("actorId") actorId: String?, @QueryParam("action") action: String?,
        @QueryParam("resourceType") resourceType: String?, @QueryParam("resourceId") resourceId: String?,
        @QueryParam("outcome") outcome: String?, @QueryParam("q") q: String?,
    ): Response {
        val kind = (format ?: "json").lowercase()
        if (kind != "json" && kind != "csv") return error(400, "format must be csv or json")
        val filter = scope(tenantId, from, to, actorId, action, resourceType, resourceId, outcome, q)
            .let { it as? Filter ?: return it as Response }
        val rows = events(filter, EXPORT_MAX, 0)
        // AUD-07: the export is itself an audited action.
        audit.record(currentUser()?.id, filter.tenantId, "audit.exported", "audit_event", null,
            mapOf("format" to kind, "records" to rows.size))
        val name = "audit-${Instant.now().toString().take(19).replace(":", "")}"
        return if (kind == "json") {
            Response.ok(mapper.writeValueAsString(rows.map(::dto)), MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"$name.json\"").build()
        } else {
            val header = "occurredAt,action,outcome,resourceType,resourceId,actorId,tenantId,requestId,metadata"
            val body = rows.joinToString("\n") { e ->
                listOf(e.occurredAt, e.action, e.outcome, e.resourceType, e.resourceId, e.actorId, e.tenantId, e.requestId, e.metadata)
                    .joinToString(",") { csv(it?.toString()) }
            }
            Response.ok(header + "\n" + body + "\n", "text/csv")
                .header("Content-Disposition", "attachment; filename=\"$name.csv\"").build()
        }
    }

    /** RFC 4180 quoting; a leading formula character is defused so a spreadsheet does not run it. */
    private fun csv(value: String?): String {
        if (value == null) return ""
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    companion object {
        const val EXPORT_MAX = 10_000
    }
}
