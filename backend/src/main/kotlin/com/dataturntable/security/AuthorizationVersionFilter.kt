package com.dataturntable.security

import com.dataturntable.auth.User
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Provider
@Priority(Priorities.AUTHORIZATION)
class AuthorizationVersionFilter : ContainerRequestFilter {
    @Inject
    lateinit var identity: SecurityIdentity

    @PersistenceContext
    lateinit var em: EntityManager

    override fun filter(requestContext: ContainerRequestContext) {
        if (identity.isAnonymous) return
        val token = identity.principal as? JsonWebToken ?: return
        // This one endpoint consumes a provider ID token, then resolves an explicit local link.
        // Every other authenticated request must still carry our local authorization version.
        if (identity.getAttribute<String>("tenant-id") == "external" &&
            requestContext.uriInfo.path.trimStart('/') == "api/internal/v1/auth/oidc/login") return
        val userId = try { UUID.fromString(token.subject) } catch (_: Exception) {
            requestContext.abortWith(Response.status(401).entity(mapOf("error" to "Unauthorized")).build())
            return
        }
        val user = em.find(User::class.java, userId)
        // JWT implementations may expose numeric claims as a Number or a JSON/string value.
        // Normalize both forms so a freshly issued token is not spuriously rejected.
        val rawTokenVersion = token.getClaim<Any>("authorizationVersion")
        val tokenVersion = when (rawTokenVersion) {
            is Number -> rawTokenVersion.toLong()
            else -> rawTokenVersion?.toString()?.toLongOrNull()
        }
        if (user == null || !user.isActive || tokenVersion == null || tokenVersion != user.authorizationVersion) {
            requestContext.abortWith(Response.status(401).entity(mapOf("error" to "Authorization revoked")).build())
        }
    }
}
