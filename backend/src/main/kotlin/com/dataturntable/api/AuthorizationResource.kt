package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.authorization.AuthorizationGrantService
import com.dataturntable.security.SecurityUtils
import com.dataturntable.tenant.Membership
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/api/internal/v1/authorization")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
class AuthorizationResource {
    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var authorizationGrantService: AuthorizationGrantService

    private fun currentUser(): User? = try {
        jwt.subject?.let { em.find(User::class.java, UUID.fromString(it)) }
    } catch (_: Exception) {
        null
    }

    @GET
    @Path("/explain")
    fun explain(
        @QueryParam("userId") userId: String?,
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("ability") ability: String?
    ): Response {
        val requester = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val targetUserId = try { UUID.fromString(userId) } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid userId")).build()
        }
        val targetTenantId = try { UUID.fromString(tenantId) } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
        }
        val requestedAbility = ability?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Response.status(400).entity(mapOf("error" to "ability is required")).build()

        if (!requester.isSuperAdmin() &&
            (!SecurityUtils.canAccessTenant(requester, targetTenantId.toString()) || !requester.hasAbility("manage:tenant"))
        ) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val target = em.find(User::class.java, targetUserId)
            ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()
        val membership = em.createQuery(
            "SELECT m FROM Membership m LEFT JOIN FETCH m.role WHERE m.authUser.id = :userId AND m.tenant.id = :tenantId",
            Membership::class.java
        )
            .setParameter("userId", targetUserId)
            .setParameter("tenantId", targetTenantId)
            .resultList
            .firstOrNull()
            ?: return Response.status(404).entity(mapOf("error" to "User has no membership in tenant")).build()

        val sources = linkedMapOf<String, List<String>>()
        sources["platformAdmin"] = if (target.isSuperAdmin()) listOf("*") else emptyList()
        sources["user"] = (authorizationGrantService.legacyTokens(target.abilities) + target.grantedAbilities.map { it.value }).distinct()
        sources["membership"] = (authorizationGrantService.legacyTokens(membership.abilities) + membership.grantedAbilities.map { it.value }).distinct()
        sources["role"] = membership.role?.getAbilitiesList() ?: emptyList()
        val effective = sources.values.flatten().toSet()
        val granted = target.isSuperAdmin() || "*" in effective || requestedAbility in effective

        return Response.ok(
            mapOf(
                "userId" to targetUserId,
                "tenantId" to targetTenantId,
                "ability" to requestedAbility,
                "granted" to granted,
                "sources" to sources,
                "effectiveAbilities" to effective.sorted()
            )
        ).build()
    }
}
