package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.audit.AuditService
import com.dataturntable.authorization.AuthorizationGrantService
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.security.SecurityUtils
import com.dataturntable.service.GrafanaSyncService
import io.quarkus.security.Authenticated
import org.eclipse.microprofile.jwt.JsonWebToken
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper

@Path("/api/internal/v1/tenants/{tenantId}/memberships")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
/**
 * Defines the class `MembershipResource`, the contract implemented by this source unit.
 */
class MembershipResource {
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var grafanaSyncService: GrafanaSyncService

    @Inject
    lateinit var authorizationGrantService: AuthorizationGrantService

    @Inject
    lateinit var audit: AuditService

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun getCurrentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    /**
     * Evaluates the named capability or state predicate.
     */
    private fun isRootTenantAdmin(userId: UUID): Boolean {
        val memberships = em.createQuery(
            "SELECT m FROM Membership m WHERE m.authUser.id = :userId",
            Membership::class.java
        )
            .setParameter("userId", userId)
            .resultList
        return memberships.any { membership ->
            val roleName = membership.role?.name?.replace(" ", "")?.lowercase()
            membership.tenant?.parent == null && roleName == "tenantadmin"
        }
    }

    /**
     * Evaluates the named capability or state predicate.
     */
    private fun isRoleAssignableToTenant(role: Role, tenant: Tenant): Boolean {
        val roleTenantId = role.tenant?.id
        return roleTenantId == null || roleTenantId == tenant.id
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseTenantUuid(tenantId: String): UUID {
        return try {
            UUID.fromString(tenantId)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build())
        }
    }

    /**
     * Converts a value into the representation required by its caller.
     */
    private fun validity(request: Map<String, Any>, existing: Membership? = null): Pair<java.time.Instant?, java.time.Instant?> {
        fun parse(key: String, previous: java.time.Instant?): java.time.Instant? {
            if (!request.containsKey(key)) return previous
            val raw = request[key] ?: return null
            return (raw as? String)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: throw BadRequestException("$key must be an ISO-8601 instant or null")
        }
        val from = parse("validFrom", existing?.validFrom)
        val until = parse("validUntil", existing?.validUntil)
        if (from != null && until != null && !from.isBefore(until)) throw BadRequestException("validUntil must be after validFrom")
        return from to until
    }

    private fun toMembershipDto(membership: Membership): Map<String, Any?> {
        return mapOf(
            "id" to membership.id,
            "validFrom" to membership.validFrom,
            "validUntil" to membership.validUntil,
            "effective" to membership.isEffective(),
            "authUserId" to membership.authUser?.id,
            "authUserEmail" to membership.authUser?.email,
            "authUserFirstName" to membership.authUser?.firstName,
            "authUserLastName" to membership.authUser?.lastName,
            "roleId" to membership.role?.id,
            "roleName" to membership.role?.name,
            "abilities" to (authorizationGrantService.legacyTokens(membership.abilities) +
                membership.grantedAbilities.map { it.value }).distinct()
        )
    }

    @GET
    /**
     * Retrieves or enumerates the requested state.
     */
    fun list(@PathParam("tenantId") tenantId: String): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val tenantUuid = try {
            parseTenantUuid(tenantId)
        } catch (e: WebApplicationException) {
            return e.response
        }

        val memberships = em.createQuery(
            "SELECT m FROM Membership m LEFT JOIN FETCH m.authUser LEFT JOIN FETCH m.role WHERE m.tenant.id = :tenantId",
            Membership::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList
        return Response.ok(memberships.map { toMembershipDto(it) }).build()
    }

    @POST
    @Transactional
    /**
     * Creates or persists the requested state.
     */
    fun create(@PathParam("tenantId") tenantId: String, request: Map<String, Any>): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val authUserId = request["authUserId"] as? String ?: return Response.status(400).entity(mapOf("error" to "authUserId is required")).build()
        val roleId = request["roleId"] as? String ?: return Response.status(400).entity(mapOf("error" to "roleId is required")).build()

        val tenantUuid = try {
            parseTenantUuid(tenantId)
        } catch (e: WebApplicationException) {
            return e.response
        }

        val tenant = em.find(Tenant::class.java, tenantUuid)
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        val user = em.find(com.dataturntable.auth.User::class.java, UUID.fromString(authUserId))
            ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()
        val role = em.find(Role::class.java, UUID.fromString(roleId))
            ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()
        if (!isRoleAssignableToTenant(role, tenant)) {
            return Response.status(400).entity(mapOf("error" to "Role does not belong to tenant")).build()
        }
        if (role.isDisabled) return Response.status(400).entity(mapOf("error" to "Role is disabled")).build()

        val existingMembership = em.createQuery(
            "SELECT m FROM Membership m WHERE m.authUser.id = :userId AND m.tenant.id = :tenantId",
            Membership::class.java
        )
            .setParameter("userId", user.id)
            .setParameter("tenantId", tenant.id)
            .resultList
            .firstOrNull()
        if (existingMembership != null) {
            return Response.status(409).entity(mapOf("error" to "Membership already exists for this user and tenant")).build()
        }

        if (tenant.parent != null && isRootTenantAdmin(user.id!!)) {
            return Response.status(409).entity(mapOf("error" to "Root-tenant admins cannot hold sub-tenant memberships")).build()
        }

        val (validFrom, validUntil) = validity(request)
        val grantedAbilities = authorizationGrantService.normalize(request["abilities"] as? Collection<*>)
        val membership = Membership().apply {
            this.authUser = user
            this.validFrom = validFrom
            this.validUntil = validUntil
            this.tenant = tenant
            this.role = role
            this.abilities = objectMapper.writeValueAsString(grantedAbilities)
            this.createdAt = java.time.Instant.now()
        }
        membership.persist()
        authorizationGrantService.replaceMembershipAbilities(membership, grantedAbilities)
        grafanaSyncService.queueMembershipSync(membership)
        membership.authUser?.let { grafanaSyncService.queueUserSync(it) }
        audit.record(
            actorId = currentUser.id,
            tenantId = tenantUuid,
            action = "identity.membership.created",
            resourceType = "membership",
            resourceId = membership.id.toString(),
            metadata = mapOf("outcome" to "succeeded", "userId" to user.id.toString(), "roleId" to role.id.toString(), "validFrom" to validFrom, "validUntil" to validUntil)
        )

        return Response.status(201).entity(toMembershipDto(membership)).build()
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    /**
     * Removes, stops, or releases the requested state.
     */
    fun delete(@PathParam("tenantId") tenantId: String, @PathParam("id") id: String): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val membership = em.find(Membership::class.java, UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Membership not found")).build()
        if (membership.tenant?.id.toString() != tenantId) {
            return Response.status(404).entity(mapOf("error" to "Membership not found")).build()
        }
        val membershipId = membership.id
        val membershipTenantId = membership.tenant?.id
        val membershipTenantName = membership.tenant?.name
        val membershipUserId = membership.authUser?.id
        val membershipUserEmail = membership.authUser?.email
        membership.delete()
        membership.authUser?.let { it.authorizationVersion++ }
        if (membershipId != null) {
            grafanaSyncService.queueMembershipDeletion(
                membershipId,
                membershipTenantId,
                membershipUserId,
                membershipTenantName,
                membershipUserEmail
            )
        }
        membership.authUser?.let { grafanaSyncService.queueUserSync(it) }
        audit.record(
            actorId = currentUser.id,
            tenantId = parseTenantUuid(tenantId),
            action = "identity.membership.deleted",
            resourceType = "membership",
            resourceId = id,
            metadata = mapOf("outcome" to "succeeded")
        )
        return Response.noContent().build()
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    /**
     * Updates the requested state.
     */
    fun update(@PathParam("tenantId") tenantId: String, @PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val membership = em.find(Membership::class.java, UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Membership not found")).build()

        if (membership.tenant?.id.toString() != tenantId) {
            return Response.status(404).entity(mapOf("error" to "Membership not found")).build()
        }

        val (validFrom, validUntil) = validity(request, membership)
        var authorizationChanged = membership.validFrom != validFrom || membership.validUntil != validUntil
        request["roleId"]?.let {
            val role = em.find(Role::class.java, UUID.fromString(it as String))
                ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()
            if (!isRoleAssignableToTenant(role, membership.tenant!!)) {
                return Response.status(400).entity(mapOf("error" to "Role does not belong to tenant")).build()
            }
            if (role.isDisabled) return Response.status(400).entity(mapOf("error" to "Role is disabled")).build()
            membership.role = role
            authorizationChanged = true
        }
        request["abilities"]?.let {
            val grantedAbilities = authorizationGrantService.normalize(it as? Collection<*>)
            membership.abilities = objectMapper.writeValueAsString(grantedAbilities)
            authorizationGrantService.replaceMembershipAbilities(membership, grantedAbilities)
            authorizationChanged = true
        }
        membership.validFrom = validFrom
        membership.validUntil = validUntil
        if (authorizationChanged) membership.authUser?.authorizationVersion = (membership.authUser?.authorizationVersion ?: 0) + 1
        membership.persist()
        grafanaSyncService.queueMembershipSync(membership)
        membership.authUser?.let { grafanaSyncService.queueUserSync(it) }
        audit.record(
            actorId = currentUser.id,
            tenantId = parseTenantUuid(tenantId),
            action = "identity.membership.updated",
            resourceType = "membership",
            resourceId = membership.id.toString(),
            metadata = mapOf("outcome" to "succeeded", "authorizationChanged" to authorizationChanged, "validFrom" to validFrom, "validUntil" to validUntil)
        )

        return Response.ok(toMembershipDto(membership)).build()
    }
}
