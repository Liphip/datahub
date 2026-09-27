package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.audit.AuditService
import com.dataturntable.execution.Execution
import com.dataturntable.execution.ExecutionEnvironment
import com.dataturntable.execution.ExecutionBackendConfig
import com.dataturntable.execution.ExecutionService
import com.dataturntable.execution.ExecutionQuotaExceededException
import com.dataturntable.module.ModuleVersion
import com.dataturntable.security.SecurityUtils
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/api/internal/v1/executions")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ExecutionResource {
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var executions: ExecutionService
    @Inject lateinit var executionBackendConfig: ExecutionBackendConfig
    @Inject lateinit var audit: AuditService
    @PersistenceContext lateinit var em: EntityManager

    @POST
    fun submit(request: Map<String, Any?>): Response {
        val tenantId = parseUuid(request["tenantId"]?.toString()) ?: return bad("Invalid tenantId")
        val moduleVersionId = parseUuid(request["moduleVersionId"]?.toString()) ?: return bad("Invalid moduleVersionId")
        val user = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        if (!SecurityUtils.canAccessTenant(user, tenantId.toString()) || !user.hasAbility("pipeline:execute")) return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        val version = em.find(ModuleVersion::class.java, moduleVersionId) ?: return Response.status(404).entity(mapOf("error" to "Module version not found")).build()
        if (version.module?.tenant?.id != tenantId) return Response.status(403).entity(mapOf("error" to "Module version does not belong to tenant")).build()
        val execution = try {
            executions.submit(tenantId, moduleVersionId, ExecutionEnvironment("python:3.11-slim", backend = executionBackendConfig.backend), request["correlationId"]?.toString())
        } catch (e: ExecutionQuotaExceededException) {
            return Response.status(429).entity(mapOf("error" to "Execution quota exceeded", "scope" to e.scope, "limit" to e.limit, "active" to e.active)).build()
        }
        audit.record(
            user.id,
            tenantId,
            "execution.submitted",
            "execution",
            execution.id.toString(),
            mapOf("outcome" to "accepted", "moduleVersionId" to moduleVersionId.toString())
        )
        return Response.status(202).entity(toMap(execution)).build()
    }

    @GET @Path("/{id}")
    fun get(@PathParam("id") rawId: String): Response = withExecution(rawId) { Response.ok(toMap(it)).build() }

    @POST @Path("/{id}/cancel")
    fun cancel(@PathParam("id") rawId: String): Response = withExecution(rawId) {
        if (!executions.cancel(it)) Response.status(409).entity(mapOf("error" to "Execution is already terminal")).build()
        else {
            audit.record(
                currentUser()?.id,
                it.tenantId,
                "execution.cancelled",
                "execution",
                it.id.toString(),
                mapOf("outcome" to "accepted")
            )
            Response.ok(toMap(it)).build()
        }
    }

    private fun withExecution(rawId: String, action: (Execution) -> Response): Response {
        val id = parseUuid(rawId) ?: return bad("Invalid execution id")
        val execution = executions.find(id) ?: return Response.status(404).entity(mapOf("error" to "Execution not found")).build()
        val user = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        if (!SecurityUtils.canAccessTenant(user, execution.tenantId.toString()) || !user.hasAbility("pipeline:execute")) return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        return action(execution)
    }
    private fun currentUser(): User? = parseUuid(jwt.subject)?.let { em.find(User::class.java, it) }
    private fun parseUuid(value: String?): UUID? = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    private fun bad(message: String) = Response.status(400).entity(mapOf("error" to message)).build()
    private fun toMap(e: Execution) = mapOf("id" to e.id, "tenantId" to e.tenantId, "moduleVersionId" to e.moduleVersionId, "status" to e.status.name, "backend" to e.backend, "createdAt" to e.createdAt)
}
