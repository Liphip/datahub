package com.dataturntable.api

import com.dataturntable.auth.User
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

@Path("/api/internal/v1/tenants/{tenantId}/memberships")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class MembershipResource {

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var grafanaSyncService: GrafanaSyncService

    private fun getCurrentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

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

    private fun isRoleAssignableToTenant(role: Role, tenant: Tenant): Boolean {
        val roleTenantId = role.tenant?.id
        return roleTenantId == null || roleTenantId == tenant.id
    }

    private fun parseTenantUuid(tenantId: String): UUID {
        return try {
            UUID.fromString(tenantId)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build())
        }
    }

    private fun toMembershipDto(membership: Membership): Map<String, Any?> {
        return mapOf(
            "id" to membership.id,
            "authUserId" to membership.authUser?.id,
            "authUserEmail" to membership.authUser?.email,
            "authUserFirstName" to membership.authUser?.firstName,
            "authUserLastName" to membership.authUser?.lastName,
            "roleId" to membership.role?.id,
            "roleName" to membership.role?.name,
            "abilities" to membership.abilities
        )
    }

    @GET
    fun list(@PathParam("tenantId") tenantId: String): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
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
    fun create(@PathParam("tenantId") tenantId: String, request: Map<String, Any>): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
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

        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = role
            this.abilities = request["abilities"]?.toString() ?: "[]"
            this.createdAt = java.time.Instant.now()
        }
        membership.persist()
        grafanaSyncService.queueMembershipSync(membership)
        membership.authUser?.let { grafanaSyncService.queueUserSync(it) }

        return Response.status(201).entity(toMembershipDto(membership)).build()
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("tenantId") tenantId: String, @PathParam("id") id: String): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val membership = em.find(Membership::class.java, UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Membership not found")).build()
        if (membership.tenant?.id.toString() != tenantId) {
            return Response.status(400).entity(mapOf("error" to "Membership does not belong to tenant")).build()
        }
        val membershipId = membership.id
        val membershipTenantId = membership.tenant?.id
        val membershipTenantName = membership.tenant?.name
        val membershipUserId = membership.authUser?.id
        val membershipUserEmail = membership.authUser?.email
        membership.delete()
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
        return Response.noContent().build()
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("tenantId") tenantId: String, @PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val membership = em.find(Membership::class.java, UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Membership not found")).build()

        if (membership.tenant?.id.toString() != tenantId) {
            return Response.status(400).entity(mapOf("error" to "Membership does not belong to tenant")).build()
        }

        request["roleId"]?.let {
            val role = em.find(Role::class.java, UUID.fromString(it as String))
                ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()
            if (!isRoleAssignableToTenant(role, membership.tenant!!)) {
                return Response.status(400).entity(mapOf("error" to "Role does not belong to tenant")).build()
            }
            membership.role = role
        }
        request["abilities"]?.let { membership.abilities = it.toString() }
        membership.persist()
        grafanaSyncService.queueMembershipSync(membership)
        membership.authUser?.let { grafanaSyncService.queueUserSync(it) }

        return Response.ok(toMembershipDto(membership)).build()
    }
}
