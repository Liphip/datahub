package com.dataturntable.api

import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleVersion
import io.quarkus.security.Authenticated
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/modules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class ModuleResource {
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String, @QueryParam("type") type: String?): Response {
        val modules = CodeModule.findByTenantId(UUID.fromString(tenantId))
        return Response.ok(modules.map { mapOf("id" to it.id, "name" to it.name, "moduleType" to it.moduleType?.name) }).build()
    }
    
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val moduleType = request["moduleType"] as? String ?: return Response.status(400).entity(mapOf("error" to "Module type is required")).build()
        
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        }
        
        val tenant = com.dataturntable.tenant.Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val module = CodeModule().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.moduleType = com.dataturntable.module.ModuleType.valueOf(moduleType.uppercase())
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        module.persist()
        
        return Response.status(201).entity(mapOf("id" to module.id, "name" to module.name)).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val module = CodeModule.findById(UUID.fromString(id))
        return if (module != null) {
            Response.ok(mapOf("id" to module.id, "name" to module.name, "moduleType" to module.moduleType?.name)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Module not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val module = CodeModule.findById(UUID.fromString(id))
        return if (module != null) {
            module.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Module not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/versions")
    fun getVersions(@PathParam("id") id: String): Response {
        val versions = ModuleVersion.findByModuleId(UUID.fromString(id))
        return Response.ok(versions.map { mapOf("id" to it.id, "version" to it.version, "versionTag" to it.versionTag) }).build()
    }
    
    @POST
    @Path("/{id}/versions")
    @Transactional
    fun createVersion(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val module = CodeModule.findById(UUID.fromString(id))
            ?: return Response.status(404).entity(mapOf("error" to "Module not found")).build()
        
        val code = request["code"] as? String ?: return Response.status(400).entity(mapOf("error" to "Code is required")).build()
        
        val latestVersion = ModuleVersion.findByModuleId(UUID.fromString(id))
            .maxByOrNull { it.version }?.version ?: 0
        
        val version = ModuleVersion().apply {
            this.module = module
            this.version = latestVersion + 1
            this.versionTag = request["versionTag"] as? String
            this.code = code
            this.requirements = request["requirements"] as? String ?: ""
            this.config = request["config"] as? String ?: "{}"
            this.isActive = true
            this.createdAt = java.time.Instant.now()
        }
        version.persist()
        
        return Response.status(201).entity(mapOf("id" to version.id, "version" to version.version)).build()
    }
}
