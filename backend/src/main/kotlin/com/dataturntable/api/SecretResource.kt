package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.audit.AuditService
import com.dataturntable.secrets.SecretService
import com.dataturntable.security.SecurityUtils
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/** Secret management deliberately exposes references only; plaintext is write-only. */
@Path("/api/internal/v1/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class SecretResource {
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var secrets: SecretService
    @Inject lateinit var audit: AuditService
    @PersistenceContext lateinit var em: EntityManager

    private fun currentUser(): User? = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.let { em.find(User::class.java, it) }

    /**
     * Listing secrets needs `secret:manage`. Creating or rotating one is also allowed to users who
     * configure connectors or alert channels: values are write-only, and a secret only takes
     * effect through a configuration those users may edit anyway.
     */
    private fun authorize(tenantId: String, vararg abilities: String = arrayOf("secret:manage")): Response? {
        val user = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        if (!SecurityUtils.canAccessTenant(user, tenantId)) return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        if (abilities.none { SecurityUtils.hasAbility(user, it) }) return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        return null
    }

    @GET
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val id = runCatching { UUID.fromString(tenantId) }.getOrNull()
            ?: return Response.status(400).entity(mapOf("error" to "Valid tenantId is required")).build()
        authorize(id.toString())?.let { return it }
        return Response.ok(secrets.references(id).map { mapOf("id" to it.id, "name" to it.name, "version" to it.version) }).build()
    }

    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantId: String?, request: Map<String, Any?>): Response {
        val id = runCatching { UUID.fromString(tenantId) }.getOrNull()
            ?: return Response.status(400).entity(mapOf("error" to "Valid tenantId is required")).build()
        authorize(id.toString(), *WRITERS)?.let { return it }
        val name = request["name"]?.toString() ?: return Response.status(400).entity(mapOf("error" to "name is required")).build()
        val value = request["value"]?.toString() ?: return Response.status(400).entity(mapOf("error" to "value is required")).build()
        return try {
            val reference = secrets.create(id, name, value)
            audit.record(currentUser()?.id, id, "secret.created", "secret", reference.id.toString(), mapOf("name" to name, "value" to value))
            Response.status(201).entity(mapOf("id" to reference.id, "name" to reference.name, "version" to reference.version)).build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).entity(mapOf("error" to e.message)).build()
        }
    }

    @POST
    @Path("/{id}/rotate")
    @Transactional
    fun rotate(@PathParam("id") secretId: String, @QueryParam("tenantId") tenantId: String?, request: Map<String, Any?>): Response {
        val tenant = runCatching { UUID.fromString(tenantId) }.getOrNull()
            ?: return Response.status(400).entity(mapOf("error" to "Valid tenantId is required")).build()
        val secret = runCatching { UUID.fromString(secretId) }.getOrNull()
            ?: return Response.status(400).entity(mapOf("error" to "Valid secret ID is required")).build()
        authorize(tenant.toString(), *WRITERS)?.let { return it }
        val value = request["value"]?.toString() ?: return Response.status(400).entity(mapOf("error" to "value is required")).build()
        return try {
            val reference = secrets.rotate(tenant, secret, value)
            audit.record(currentUser()?.id, tenant, "secret.rotated", "secret", reference.id.toString(), mapOf("name" to reference.name, "version" to reference.version, "value" to value))
            Response.ok(mapOf("id" to reference.id, "name" to reference.name, "version" to reference.version)).build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).entity(mapOf("error" to e.message)).build()
        }
    }

    companion object {
        private val WRITERS = arrayOf("secret:manage", "connector:write", "alert:configure")
    }
}
