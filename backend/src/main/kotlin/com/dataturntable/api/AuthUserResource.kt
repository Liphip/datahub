package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import com.dataturntable.service.GrafanaSyncService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import io.quarkus.elytron.security.common.BcryptUtil
import java.util.UUID

@Path("/api/internal/v1/auth-users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
/**
 * Defines the class `AuthUserResource`, the contract implemented by this source unit.
 */
class AuthUserResource {
    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var grafanaSyncService: GrafanaSyncService

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun getTenantIdFromToken(): String? {
        val tid = jwt.getClaim("tenantId") as? String
        return if (tid.isNullOrBlank()) null else tid
    }

    /**
     * Implements `currentUser` for this source unit.
     */
    private fun currentUser(): User? {
        return try {
            jwt.subject?.let { em.find(User::class.java, UUID.fromString(it)) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Implements `resolveTenantIdOrBadRequest` for this source unit.
     */
    private fun resolveTenantIdOrBadRequest(tenantIdQuery: String?): String? {
        return tenantIdQuery ?: getTenantIdFromToken()
    }

    /**
     * Implements `userHasMembershipInTenant` for this source unit.
     */
    private fun userHasMembershipInTenant(user: User, tenantId: UUID): Boolean {
        return user.memberships.any { membership -> membership.tenant?.id == tenantId }
    }

    /**
     * Implements `extractPasswordHash` for this source unit.
     */
    private fun extractPasswordHash(request: Map<String, Any>): String? {
        return (request["passwordHash"] as? String)?.takeIf { it.isNotBlank() }
            ?: (request["password"] as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Evaluates the named capability or state predicate.
     */
    private fun hashForStorage(passwordHash: String): String {
        return BcryptUtil.bcryptHash(passwordHash)
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
    }

    @GET
    /**
     * Retrieves or enumerates the requested state.
     */
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val resolvedTenantId = resolveTenantIdOrBadRequest(tenantId)
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val tenantUuid = try {
            parseUuid(resolvedTenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val currentUser = currentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, resolvedTenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "read:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val users = em.createQuery(
            "SELECT DISTINCT u FROM User u JOIN u.memberships m WHERE m.tenant.id = :tenantId",
            User::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList
        return Response.ok(
            users.map {
                mapOf(
                    "id" to it.id,
                    "email" to it.email,
                    "firstName" to it.firstName,
                    "lastName" to it.lastName,
                    "isActive" to it.isActive
                )
            }
        ).build()
    }

    @POST
    @Transactional
    /**
     * Creates or persists the requested state.
     */
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val currentUser = currentUser()
        val tenantId = resolveTenantIdOrBadRequest(tenantIdQuery)
        if (currentUser == null || tenantId == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "write:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val email = request["email"] as? String
            ?: return Response.status(400).entity(mapOf("error" to "Email is required")).build()
        val passwordHash = extractPasswordHash(request)
            ?: return Response.status(400).entity(mapOf("error" to "Password hash is required")).build()

        val existingUser = em.createQuery("SELECT u FROM User u WHERE u.email = :email", User::class.java)
            .setParameter("email", email)
            .resultList
            .firstOrNull()
        if (existingUser != null) {
            return Response.status(409).entity(mapOf("error" to "Auth user already exists")).build()
        }

        val user = User().apply {
            this.email = email
            this.passwordHash = hashForStorage(passwordHash)
            this.firstName = request["firstName"] as? String
            this.lastName = request["lastName"] as? String
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        user.persist()
        grafanaSyncService.queueUserSync(user)

        return Response.status(201).entity(mapOf("id" to user.id, "email" to user.email)).build()
    }

    @GET
    @Path("/{id}")
    /**
     * Retrieves or enumerates the requested state.
     */
    fun get(@PathParam("id") id: String, @QueryParam("tenantId") tenantIdQuery: String?): Response {
        val userId = try {
            parseUuid(id, "user id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val user = em.find(User::class.java, userId)
            ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()

        val tenantId = resolveTenantIdOrBadRequest(tenantIdQuery)
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val currentUser = currentUser()
        if (currentUser == null || tenantId == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "read:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        if (!userHasMembershipInTenant(user, tenantUuid)) {
            return Response.status(404).entity(mapOf("error" to "User not found")).build()
        }

        return Response.ok(
            mapOf(
                "id" to user.id,
                "email" to user.email,
                "firstName" to user.firstName,
                "lastName" to user.lastName,
                "isActive" to user.isActive
            )
        ).build()
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    /**
     * Updates the requested state.
     */
    fun update(@PathParam("id") id: String, @QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val userId = try {
            parseUuid(id, "user id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val user = em.find(User::class.java, userId)
            ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()

        val tenantId = resolveTenantIdOrBadRequest(tenantIdQuery)
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val currentUser = currentUser()
        if (currentUser == null || tenantId == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "write:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        if (!userHasMembershipInTenant(user, tenantUuid)) {
            return Response.status(404).entity(mapOf("error" to "User not found")).build()
        }

        request["email"]?.let { user.email = it as String }
        extractPasswordHash(request)?.let { user.passwordHash = hashForStorage(it) }
        request["firstName"]?.let { user.firstName = it as String }
        request["lastName"]?.let { user.lastName = it as String }
        request["isActive"]?.let { user.isActive = it as Boolean }
        user.updatedAt = java.time.Instant.now()
        user.persist()
        grafanaSyncService.queueUserSync(user)

        return Response.ok(mapOf("id" to user.id, "email" to user.email)).build()
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    /**
     * Removes, stops, or releases the requested state.
     */
    fun delete(@PathParam("id") id: String, @QueryParam("tenantId") tenantIdQuery: String?): Response {
        val userId = try {
            parseUuid(id, "user id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val user = em.find(User::class.java, userId)
            ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()

        val tenantId = resolveTenantIdOrBadRequest(tenantIdQuery)
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val tenantUuid = try {
            parseUuid(tenantId, "tenantId")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val currentUser = currentUser()
        if (currentUser == null || tenantId == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "delete:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        if (!userHasMembershipInTenant(user, tenantUuid)) {
            return Response.status(404).entity(mapOf("error" to "User not found")).build()
        }

        val membershipToRemove = user.memberships.firstOrNull { it.tenant?.id == tenantUuid }
            ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()
        val removedMembershipId = membershipToRemove.id
        val removedTenantId = membershipToRemove.tenant?.id
        val removedTenantName = membershipToRemove.tenant?.name
        val removedUserId = membershipToRemove.authUser?.id
        val removedUserEmail = membershipToRemove.authUser?.email
        membershipToRemove.delete()
        if (removedMembershipId != null) {
            grafanaSyncService.queueMembershipDeletion(
                removedMembershipId,
                removedTenantId,
                removedUserId,
                removedTenantName,
                removedUserEmail
            )
        }

        val remainingMemberships = em.createQuery(
            "SELECT COUNT(m) FROM Membership m WHERE m.authUser.id = :userId",
            java.lang.Long::class.java
        )
            .setParameter("userId", userId)
            .singleResult

        if (remainingMemberships == 0L) {
            grafanaSyncService.queueUserDeletion(userId, user.email, user.firstName, user.lastName)
            user.delete()
        } else {
            grafanaSyncService.queueUserSync(user)
        }
        return Response.noContent().build()
    }
}
