package com.dataturntable.api

import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
import com.dataturntable.service.TenantService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class TenantResource {
    
    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var tenantService: TenantService

    @Inject
    lateinit var jwt: JsonWebToken

    private fun getCurrentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    private fun requireSuperadmin(): Response? {
        val user = getCurrentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        if (!user.isSuperAdmin()) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        return null
    }
    
    @GET
    fun list(): Response {
        val tenants = em.createQuery("SELECT t FROM Tenant t WHERE t.parent IS NULL", Tenant::class.java).resultList
        return Response.ok(tenants.map { mapOf("id" to it.id, "name" to it.name) }).build()
    }
    
    @POST
    @Transactional
    fun create(request: Map<String, Any>): Response {
        requireSuperadmin()?.let { return it }

        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        
        val parentId = request["parentId"] as? String
        val parent = parentId?.let { em.find(Tenant::class.java, UUID.fromString(it)) }
        
        if (parent == null) {
            val rootTenants = em.createQuery("SELECT t FROM Tenant t WHERE t.parent IS NULL", Tenant::class.java).resultList
            if (rootTenants.isNotEmpty()) {
                return Response.status(400).entity(mapOf("error" to "Root tenant already exists. Create a sub-tenant instead.")).build()
            }
        }
        
        val tenant = Tenant().apply {
            this.name = name
            this.parent = parent
            this.settings = "{}"
            this.quota = """{"max_users": 100, "max_pipelines": 50, "max_storage_mb": 10240}"""
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        em.persist(tenant)
        tenantService.createDefaultRoles(tenant)
        return Response.status(201).entity(mapOf("id" to tenant.id, "name" to tenant.name)).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val tenant = em.find(Tenant::class.java, UUID.fromString(id))
        return if (tenant != null) {
            Response.ok(mapOf("id" to tenant.id, "name" to tenant.name, "settings" to tenant.settings)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        requireSuperadmin()?.let { return it }

        val tenant = em.find(Tenant::class.java, UUID.fromString(id))
        return if (tenant != null) {
            request["name"]?.let { tenant.name = it as String }
            request["settings"]?.let { tenant.settings = it as String }
            request["quota"]?.let { tenant.quota = it as String }
            tenant.updatedAt = java.time.Instant.now()
            em.merge(tenant)
            Response.ok(mapOf("id" to tenant.id, "name" to tenant.name)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        requireSuperadmin()?.let { return it }

        val tenant = em.find(Tenant::class.java, UUID.fromString(id))
        return if (tenant != null) {
            if (tenant.parent == null) {
                val rootCount = em.createQuery("SELECT COUNT(t) FROM Tenant t WHERE t.parent IS NULL", java.lang.Long::class.java).singleResult
                if (rootCount <= 1) {
                    return Response.status(400).entity(mapOf("error" to "Cannot delete the last root tenant")).build()
                }
            }
            em.remove(tenant)
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/children")
    fun getChildren(@PathParam("id") id: String): Response {
        val children = em.createQuery("SELECT t FROM Tenant t WHERE t.parent.id = :parentId", Tenant::class.java)
            .setParameter("parentId", UUID.fromString(id))
            .resultList
        return Response.ok(children.map { mapOf("id" to it.id, "name" to it.name) }).build()
    }
}
