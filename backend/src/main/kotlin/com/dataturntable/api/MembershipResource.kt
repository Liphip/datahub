package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.security.SecurityUtils
import org.eclipse.microprofile.jwt.JsonWebToken
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/tenants/{tenantId}/memberships")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MembershipResource {

    @Inject
    lateinit var jwt: JsonWebToken

    @GET
    fun list(@PathParam("tenantId") tenantId: String): Response {
        val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val memberships = Membership.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(memberships.map { m -> mapOf(
            "id" to m.id,
            "authUserId" to m.authUser?.id,
            "roleId" to m.role?.id,
            "abilities" to m.abilities
        ) }).build()
    }

    @POST
    @Transactional
    fun create(@PathParam("tenantId") tenantId: String, request: Map<String, Any>): Response {
        val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val authUserId = request["authUserId"] as? String ?: return Response.status(400).entity(mapOf("error" to "authUserId is required")).build()
        val roleId = request["roleId"] as? String ?: return Response.status(400).entity(mapOf("error" to "roleId is required")).build()

        val tenant = Tenant.findById(UUID.fromString(tenantId)) ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        val user = com.dataturntable.auth.User.findById(UUID.fromString(authUserId)) ?: return Response.status(404).entity(mapOf("error" to "User not found")).build()
        val role = Role.findById(UUID.fromString(roleId)) ?: return Response.status(404).entity(mapOf("error" to "Role not found")).build()

        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = role
            this.abilities = "[]"
            this.createdAt = java.time.Instant.now()
        }
        membership.persist()

        return Response.status(201).entity(mapOf("id" to membership.id)).build()
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("tenantId") tenantId: String, @PathParam("id") id: String): Response {
        val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "manage:tenant")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val membership = Membership.findById(UUID.fromString(id)) ?: return Response.status(404).entity(mapOf("error" to "Membership not found")).build()
        membership.delete()
        return Response.noContent().build()
    }
}
