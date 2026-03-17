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

    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
    }

    private fun toTenantMap(tenant: Tenant, children: List<Map<String, Any?>>? = null): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to tenant.id,
            "name" to tenant.name,
            "parentId" to tenant.parent?.id,
            "createdAt" to tenant.createdAt,
            "updatedAt" to tenant.updatedAt
        )
        if (children != null) {
            base["children"] = children
        }
        return base
    }

    private fun buildTenantTree(tenants: List<Tenant>, parentId: UUID?, query: String?): List<Map<String, Any?>> {
        val nodes = tenants.filter { it.parent?.id == parentId }
        val normalizedQuery = query?.trim()?.lowercase()

        return nodes.mapNotNull { tenant ->
            val children = buildTenantTree(tenants, tenant.id, normalizedQuery)
            val matchesSelf = normalizedQuery.isNullOrBlank() || tenant.name?.lowercase()?.contains(normalizedQuery) == true

            if (!matchesSelf && children.isEmpty()) {
                null
            } else {
                toTenantMap(tenant, children)
            }
        }
    }
    
    @GET
    fun list(@QueryParam("recursive") recursive: String?, @QueryParam("query") query: String?): Response {
        if (recursive.equals("true", ignoreCase = true)) {
            val allTenants = em.createQuery("SELECT t FROM Tenant t", Tenant::class.java).resultList
            return Response.ok(buildTenantTree(allTenants, null, query)).build()
        }

        val tenants = em.createQuery("SELECT t FROM Tenant t WHERE t.parent IS NULL", Tenant::class.java).resultList
        val normalizedQuery = query?.trim()?.lowercase()
        val filtered = if (normalizedQuery.isNullOrBlank()) {
            tenants
        } else {
            tenants.filter { it.name?.lowercase()?.contains(normalizedQuery) == true }
        }
        return Response.ok(filtered.map { toTenantMap(it) }).build()
    }
    
    @POST
    @Transactional
    fun create(request: Map<String, Any>): Response {
        requireSuperadmin()?.let { return it }

        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        
        val parentId = request["parentId"] as? String
        val parent = try {
            parentId?.let { em.find(Tenant::class.java, parseUuid(it, "parentId")) }
        } catch (e: WebApplicationException) {
            return e.response
        }
        
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
        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val tenant = em.find(Tenant::class.java, tenantId)
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

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val tenant = em.find(Tenant::class.java, tenantId)
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

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        val tenant = em.find(Tenant::class.java, tenantId)
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
        val parentId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }

        val children = em.createQuery("SELECT t FROM Tenant t WHERE t.parent.id = :parentId", Tenant::class.java)
            .setParameter("parentId", parentId)
            .resultList
        return Response.ok(children.map { toTenantMap(it) }).build()
    }
}
