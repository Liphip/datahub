package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import org.eclipse.microprofile.jwt.JsonWebToken
import jakarta.inject.Inject
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.security.Authenticated
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class UserResource {
    @Inject
    lateinit var jwt: JsonWebToken
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String): Response {
        val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "read:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        val users = User.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(users.map { mapOf(
            "id" to it.id,
            "email" to it.email,
            "firstName" to it.firstName,
            "tenantId" to it.getPrimaryTenant()?.id?.toString()
        ) }).build()
    }
    
    @POST
    @Transactional
    fun create(request: Map<String, Any>): Response {
        val tenantId = request["tenantId"] as? String ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "write:user")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        val email = request["email"] as? String ?: return Response.status(400).entity(mapOf("error" to "Email is required")).build()
        val passwordHash = request["passwordHash"] as? String ?: return Response.status(400).entity(mapOf("error" to "Password hash is required")).build()
        
        val tenant = Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val user = User().apply {
            this.email = email
            this.passwordHash = passwordHash
            this.firstName = request["firstName"] as? String
            this.lastName = request["lastName"] as? String
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        user.persist()
        
        // Create default membership
        val membership = Membership().apply {
            this.authUser = user
            this.tenant = tenant
            this.role = Role.findDefaultForTenant(tenant?.id ?: UUID.fromString("00000000-0000-0000-0000-000000000001")) ?: Role.findById(UUID.fromString("00000000-0000-0000-0000-000000000002")) // Viewer role
            this.abilities = "[]"
            this.createdAt = java.time.Instant.now()
        }
        membership.persist()
        
        return Response.status(201).entity(mapOf("id" to user.id, "email" to user.email)).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val user = User.findById(UUID.fromString(id))
        return if (user != null) {
            val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
            if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, user.getPrimaryTenant()?.id.toString())) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "read:user")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            Response.ok(mapOf(
                "id" to user.id,
                "email" to user.email,
                "firstName" to user.firstName,
                "tenantId" to user.getPrimaryTenant()?.id.toString()
            )).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val user = User.findById(UUID.fromString(id))
        return if (user != null) {
            val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
            if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, user.getPrimaryTenant()?.id.toString())) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "delete:user")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            // Delete memberships first
            user.memberships.forEach { it.delete() }
            user.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val user = User.findById(UUID.fromString(id))
        return if (user != null) {
            val currentUser = try { jwt.subject?.let { User.findById(UUID.fromString(it)) } } catch (_: Exception) { null }
            if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, user.getPrimaryTenant()?.id.toString())) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "write:user")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            request["email"]?.let { user.email = it as String }
            request["passwordHash"]?.let { user.passwordHash = it as String }
            request["firstName"]?.let { user.firstName = it as String }
            request["lastName"]?.let { user.lastName = it as String }
            request["isActive"]?.let { user.isActive = it as Boolean }
            user.updatedAt = java.time.Instant.now()
            user.persist()
            Response.ok(mapOf("id" to user.id, "email" to user.email)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
}