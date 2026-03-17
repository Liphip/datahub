package com.dataturntable.api

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

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuthResource {
    
    @Inject
    lateinit var authService: AuthService
    
    @Inject
    lateinit var jwt: JsonWebToken
    
    @POST
    @Path("/login")
    fun login(@Valid request: LoginRequest): Response {
        return try {
            val response = authService.login(request)
            Response.ok(response).build()
        } catch (e: SecurityException) {
            Response.status(401).entity(mapOf("error" to "Invalid credentials")).build()
        }
    }
    
    @POST
    @Path("/register")
    fun register(@Valid request: SetupRequest): Response {
        return try {
            val setupResponse = authService.setup(request)
            val loginHash = request.passwordHash
                ?: request.password?.let { plain ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    java.util.Base64.getEncoder().encodeToString(digest.digest(plain.toByteArray()))
                }
                ?: run {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    java.util.Base64.getEncoder().encodeToString(digest.digest(setupResponse.password.toByteArray()))
                }

            val auth = authService.login(LoginRequest(request.email, loginHash, setupResponse.user.tenantId))
                ?: return Response.status(401).entity(mapOf("error" to "Unable to authenticate registered user")).build()

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
    fun setup(@Valid request: SetupRequest): Response {
        return try {
            val response = authService.setup(request)
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
    fun me(): Response {
        val user = authService.getUserFromJwt()
        return if (user != null) {
            Response.ok(user).build()
        } else {
            Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        }
    }
}
