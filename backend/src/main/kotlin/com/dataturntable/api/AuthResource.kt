package com.dataturntable.api

import com.dataturntable.dto.*
import com.dataturntable.service.AuthService
import io.quarkus.security.Authenticated
import io.quarkus.security.PermitAll
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuthResource {
    
    @Inject
    lateinit var authService: AuthService
    
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
    @PermitAll
    fun register(@Valid request: RegisterRequest): Response {
        return try {
            val response = authService.register(request)
            Response.status(201).entity(response).build()
        } catch (e: IllegalArgumentException) {
            Response.status(400).entity(mapOf("error" to (e.message ?: "Bad request"))).build()
        }
    }
    
    @GET
    @Path("/setup")
    @PermitAll
    fun checkSetup(): Response {
        val needsSetup = authService.needsSetup()
        return Response.ok(mapOf("needsSetup" to needsSetup)).build()
    }
    
    @GET
    @Path("/me")
    @Authenticated
    fun me(@Context email: String): Response {
        val user = authService.getCurrentUser(email)
        return if (user != null) {
            Response.ok(user).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
}
