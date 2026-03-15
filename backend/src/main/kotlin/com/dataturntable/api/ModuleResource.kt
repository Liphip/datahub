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
}
