package com.dataturntable.api

import com.dataturntable.auth.ExternalIdentity
import com.dataturntable.auth.User
import com.dataturntable.audit.AuditService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URI
import java.util.UUID

/** Linking a login grants access to an existing account, so only platform administrators may do it. */
@Path("/api/internal/v1/users/{userId}/identities")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ExternalIdentityResource {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var audit: AuditService

    private fun admin(): User {
        val id = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: throw NotAuthorizedException("Bearer")
        val actor = em.find(User::class.java, id) ?: throw NotAuthorizedException("Bearer")
        if (!actor.isSuperAdmin()) throw ForbiddenException()
        return actor
    }
    private fun user(id: String): User {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: throw BadRequestException("Invalid user id")
        return em.find(User::class.java, uuid) ?: throw NotFoundException()
    }
    private fun view(link: ExternalIdentity) = mapOf("id" to link.id, "issuer" to link.issuer, "subject" to link.subject)

    @GET
    fun list(@PathParam("userId") userId: String): Response {
        admin()
        val target = user(userId)
        return Response.ok(em.createQuery("SELECT i FROM ExternalIdentity i WHERE i.user = :user ORDER BY i.createdAt", ExternalIdentity::class.java)
            .setParameter("user", target).resultList.map(::view)).header("Cache-Control", "no-store").build()
    }

    @POST @Transactional
    fun link(@PathParam("userId") userId: String, request: Map<String, String>): Response {
        val actor = admin()
        val target = user(userId)
        val issuer = request["issuer"] ?: throw BadRequestException("Issuer is required")
        val uri = runCatching { URI.create(issuer) }.getOrNull()
        if (issuer.length > 512 || uri?.host == null || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null ||
            (uri.scheme != "https" && !(uri.scheme == "http" && (uri.host in setOf("localhost", "127.0.0.1", "[::1]") || uri.host.endsWith(".localhost"))))) {
            throw BadRequestException("Issuer must be an HTTPS URL (HTTP is allowed only on loopback for development)")
        }
        val subject = request["subject"]?.takeIf { it.isNotBlank() && it.length <= 255 } ?: throw BadRequestException("Subject is required (maximum 255 characters)")
        if (em.createQuery("SELECT COUNT(i) FROM ExternalIdentity i WHERE i.issuer = :issuer AND i.subject = :subject", java.lang.Long::class.java)
                .setParameter("issuer", issuer).setParameter("subject", subject).singleResult.toLong() > 0) {
            return Response.status(409).entity(mapOf("error" to "This external identity is already linked")).build()
        }
        val identity = ExternalIdentity().apply { this.issuer = issuer; this.subject = subject; this.user = target }
        em.persist(identity)
        audit.record(actorId = actor.id, tenantId = null, action = "identity.oidc.linked", resourceType = "auth_user", resourceId = userId,
            metadata = mapOf("outcome" to "succeeded", "issuer" to issuer, "subject" to subject))
        return Response.status(201).entity(view(identity)).build()
    }

    @DELETE @Path("/{id}") @Transactional
    fun unlink(@PathParam("userId") userId: String, @PathParam("id") id: String): Response {
        val actor = admin()
        val target = user(userId)
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: throw BadRequestException("Invalid identity id")
        val link = em.find(ExternalIdentity::class.java, uuid) ?: throw NotFoundException()
        if (link.user?.id != target.id) throw NotFoundException()
        em.createQuery("DELETE FROM OidcLoginTicket t WHERE t.externalIdentity = :identity").setParameter("identity", link).executeUpdate()
        em.remove(link)
        // Revoke already issued local tokens as well as pending handoffs.
        target.authorizationVersion++
        audit.record(actorId = actor.id, tenantId = null, action = "identity.oidc.unlinked", resourceType = "auth_user", resourceId = userId,
            metadata = mapOf("outcome" to "succeeded", "issuer" to link.issuer, "subject" to link.subject))
        return Response.noContent().build()
    }
}
