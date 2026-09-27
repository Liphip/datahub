package com.dataturntable.api

import com.dataturntable.audit.AuditService
import com.dataturntable.dto.*
import com.dataturntable.service.AuthService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken

@Path("/api/internal/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
/**
 * Defines the class `AuthResource`, the contract implemented by this source unit.
 */
class AuthResource {
    
    @Inject
    lateinit var authService: AuthService
    
    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var audit: AuditService
    
    @POST
    @Path("/login")
    /**
     * Implements `login` for this source unit.
     */
    fun login(@Valid request: LoginRequest): Response {
        return try {
            val response = authService.login(request) ?: throw SecurityException("Invalid credentials")
            audit.record(
                actorId = java.util.UUID.fromString(response.user.id),
                tenantId = response.user.tenantId?.let(::uuidOrNull),
                action = "auth.login.succeeded",
                resourceType = "auth_user",
                resourceId = response.user.id,
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.ok(response).build()
        } catch (e: SecurityException) {
            audit.record(
                actorId = null,
                tenantId = null,
                action = "auth.login.failed",
                resourceType = "auth_user",
                resourceId = null,
                metadata = mapOf("outcome" to "failed")
            )
            Response.status(401).entity(mapOf("error" to "Invalid credentials")).build()
        }
    }
    
    @POST
    @Path("/register")
    /**
     * Creates or persists the requested state.
     */
    fun register(@Valid request: SetupRequest): Response {
        return try {
            val setupResponse = authService.setup(request)
            val loginCredential = request.password ?: request.passwordHash ?: setupResponse.password
            val auth = authService.login(LoginRequest(request.email, password = loginCredential, tenantId = setupResponse.user.tenantId))
                ?: return Response.status(401).entity(mapOf("error" to "Unable to authenticate registered user")).build()

            audit.record(
                actorId = java.util.UUID.fromString(auth.user.id),
                tenantId = auth.user.tenantId?.let(::uuidOrNull),
                action = "auth.registration.completed",
                resourceType = "auth_user",
                resourceId = auth.user.id,
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.status(201).entity(auth).build()
        } catch (e: IllegalStateException) {
            Response.status(409).entity(mapOf("error" to (e.message ?: "Setup already completed"))).build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).entity(mapOf("error" to (e.message ?: "Bad request"))).build()
        } catch (e: SecurityException) {
            Response.status(401).entity(mapOf("error" to "Registration failed")).build()
        }
    }

    @POST
    @Path("/setup")
    /**
     * Updates the requested state.
     */
    fun setup(@Valid request: SetupRequest): Response {
        return try {
            val response = authService.setup(request)
            audit.record(
                actorId = java.util.UUID.fromString(response.user.id),
                tenantId = response.user.tenantId?.let(::uuidOrNull),
                action = "auth.setup.completed",
                resourceType = "auth_user",
                resourceId = response.user.id,
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.status(201).entity(response).build()
        } catch (e: IllegalStateException) {
            Response.status(409).entity(mapOf("error" to (e.message ?: "Setup already completed"))).build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).entity(mapOf("error" to (e.message ?: "Bad request"))).build()
        }
    }
    
    @GET
    @Path("/me")
    @Authenticated
    /**
     * Implements `me` for this source unit.
     */
    fun me(): Response {
        val user = authService.getUserFromJwt()
        return if (user != null) {
            Response.ok(user).build()
        } else {
            Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        }
    }

    @GET
    @Path("/tenants")
    @Authenticated
    /**
     * Retrieves or enumerates the requested state.
     */
    fun listTenantContexts(): Response {
        val subject = jwt.subject ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val userId = try {
            java.util.UUID.fromString(subject)
        } catch (_: Exception) {
            return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        }

        val contexts = authService.getTenantContexts(userId)
        return Response.ok(contexts).build()
    }

    @POST
    @Path("/switch-tenant")
    @Authenticated
    /**
     * Implements `switchTenant` for this source unit.
     */
    fun switchTenant(request: Map<String, String>): Response {
        val subject = jwt.subject ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val userId = try {
            java.util.UUID.fromString(subject)
        } catch (_: Exception) {
            return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        }
        val tenantId = request["tenantId"]
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()

        return try {
            val response = authService.switchTenant(userId, tenantId)
            audit.record(
                actorId = userId,
                tenantId = response.user.tenantId?.let(::uuidOrNull),
                action = "auth.tenant.switched",
                resourceType = "tenant",
                resourceId = response.user.tenantId,
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.ok(response).build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).entity(mapOf("error" to (e.message ?: "Bad request"))).build()
        } catch (e: NotFoundException) {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        } catch (e: SecurityException) {
            Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
    }

    private fun uuidOrNull(value: String): java.util.UUID? = try {
        java.util.UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
