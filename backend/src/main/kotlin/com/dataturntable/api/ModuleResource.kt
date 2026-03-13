package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.dto.*
import com.dataturntable.module.ModuleType
import com.dataturntable.service.ModuleService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import java.util.UUID

@Path("/modules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class ModuleResource {
    
    @Inject
    lateinit var moduleService: ModuleService
    
    @GET
    fun getModules(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("type") type: String?
    ): Response {
        val tenantUuid = tenantId?.let { UUID.fromString(it) }
        
        val modules = if (type != null) {
            val moduleType = ModuleType.valueOf(type.uppercase())
            moduleService.getModulesByType(tenantUuid!!, moduleType)
        } else if (tenantUuid != null) {
            moduleService.getModules(tenantUuid)
        } else {
            return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        }
        
        return Response.ok(modules).build()
    }
    
    @GET
    @Path("/{id}")
    fun getModule(@PathParam("id") id: String): Response {
        val module = moduleService.getModule(UUID.fromString(id))
        return if (module != null) {
            Response.ok(module).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Module not found")).build()
        }
    }
    
    @POST
    @Transactional
    fun createModule(
        @QueryParam("tenantId") tenantId: String,
        @Valid request: CreateModuleRequest,
        @Context securityContext: SecurityContext
    ): Response {
        // Get current user from security context
        val user = User.findAll().firstResult<User>()
        
        val module = moduleService.createModule(UUID.fromString(tenantId), request, user)
        return Response.status(201).entity(module).build()
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun deleteModule(@PathParam("id") id: String): Response {
        val deleted = moduleService.deleteModule(UUID.fromString(id))
        return if (deleted) {
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Module not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/versions")
    fun getVersions(@PathParam("id") id: String): Response {
        val versions = moduleService.getVersions(UUID.fromString(id))
        return Response.ok(versions).build()
    }
    
    @GET
    @Path("/{id}/versions/{version}")
    fun getVersion(
        @PathParam("id") id: String,
        @PathParam("version") version: Int
    ): Response {
        val versionDto = moduleService.getVersion(UUID.fromString(id), version)
        return if (versionDto != null) {
            Response.ok(versionDto).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Version not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/versions/tag/{tag}")
    fun getVersionByTag(
        @PathParam("id") id: String,
        @PathParam("tag") tag: String
    ): Response {
        val versionDto = moduleService.getVersionByTag(UUID.fromString(id), tag)
        return if (versionDto != null) {
            Response.ok(versionDto).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Version not found")).build()
        }
    }
    
    @POST
    @Path("/{id}/versions")
    @Transactional
    fun createVersion(
        @PathParam("id") id: String,
        @Valid request: CreateModuleVersionRequest,
        @Context securityContext: SecurityContext
    ): Response {
        val user = User.findAll().firstResult<User>()
        
        val version = moduleService.createVersion(UUID.fromString(id), request, user)
        return Response.status(201).entity(version).build()
    }
    
    @POST
    @Path("/{id}/versions/{version}/tag")
    @Transactional
    fun tagVersion(
        @PathParam("id") id: String,
        @PathParam("version") version: Int,
        @Valid request: TagVersionRequest
    ): Response {
        val versionDto = moduleService.tagVersion(UUID.fromString(id), version, request.versionTag)
        return if (versionDto != null) {
            Response.ok(versionDto).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Version not found")).build()
        }
    }
}
