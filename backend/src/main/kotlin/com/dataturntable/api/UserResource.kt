package com.dataturntable.api

import com.dataturntable.dto.*
import com.dataturntable.service.UserService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
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
    lateinit var userService: UserService
    
    @GET
    fun getUsers(@QueryParam("tenantId") tenantId: String?): Response {
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }
        
        val users = userService.getUsersByTenant(UUID.fromString(tenantId))
        return Response.ok(users).build()
    }
    
    @GET
    @Path("/{id}")
    fun getUser(@PathParam("id") id: String): Response {
        val user = userService.getUser(UUID.fromString(id))
        return if (user != null) {
            Response.ok(user).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @POST
    @Transactional
    fun createUser(
        @QueryParam("tenantId") tenantId: String,
        @QueryParam("password") password: String,
        @Valid request: CreateUserRequest
    ): Response {
        val user = userService.createUser(
            tenantId = UUID.fromString(tenantId),
            email = request.email,
            password = password,
            firstName = request.firstName,
            lastName = request.lastName,
            roleId = request.roleId?.let { UUID.fromString(it) },
            abilities = request.abilities
        )
        return Response.status(201).entity(user).build()
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun updateUser(
        @PathParam("id") id: String,
        @Valid request: UpdateUserRequest
    ): Response {
        val user = userService.updateUser(
            id = UUID.fromString(id),
            firstName = request.firstName,
            lastName = request.lastName,
            roleId = request.roleId?.let { UUID.fromString(it) },
            abilities = request.abilities,
            isActive = request.isActive
        )
        return if (user != null) {
            Response.ok(user).build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun deleteUser(@PathParam("id") id: String): Response {
        val deleted = userService.deleteUser(UUID.fromString(id))
        return if (deleted) {
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "User not found")).build()
        }
    }
    
    @POST
    @Path("/{id}/change-password")
    @Transactional
    fun changePassword(
        @PathParam("id") id: String,
        @Valid request: ChangePasswordRequest
    ): Response {
        return try {
            val success = userService.changePassword(
                id = UUID.fromString(id),
                oldPassword = request.oldPassword,
                newPassword = request.newPassword
            )
            if (success) {
                Response.ok(mapOf("message" to "Password changed successfully")).build()
            } else {
                Response.status(400).entity(mapOf("error" to "Failed to change password")).build()
            }
        } catch (e: SecurityException) {
            Response.status(400).entity(mapOf("error" to (e.message ?: "Invalid password"))).build()
        }
    }
}

data class CreateUserRequest(
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val roleId: String? = null,
    val abilities: List<String>? = null
)

data class UpdateUserRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val roleId: String? = null,
    val abilities: List<String>? = null,
    val isActive: Boolean? = null
)

data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String
)
