package com.dataturntable.api

import com.dataturntable.tenant.Role
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import io.quarkus.security.Authenticated
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class RoleResource {
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String?): Response {
        val roles = if (tenantId != null) {
            Role.findByTenantId(UUID.fromString(tenantId))
        } else {
            PanacheEntityBase.listAll<Role>()
        }
        return Response.ok(roles.map { mapOf("id" to it.id, "name" to it.name, "isDefault" to it.isDefault) }).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val role = Role.findById(UUID.fromString(id))
        return if (role != null) {
            Response.ok(mapOf("id" to role.id, "name" to role.name, "abilities" to role.abilities)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Role not found")).build()
        }
    }
}
