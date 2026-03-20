package com.dataturntable.api

import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleVersion
import com.dataturntable.module.ModuleType
import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/modules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class ModuleResource {

    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    private fun currentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    private fun parseModuleType(value: String): ModuleType? {
        return try {
            ModuleType.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun versionToMap(version: ModuleVersion): Map<String, Any?> {
        return mapOf(
            "id" to version.id,
            "moduleId" to version.module?.id,
            "version" to version.version,
            "versionTag" to version.versionTag,
            "code" to version.code,
            "requirements" to version.requirements,
            "config" to version.config,
            "isActive" to version.isActive,
            "createdAt" to version.createdAt
        )
    }

    private fun moduleToMap(module: CodeModule, includeVersions: Boolean = false): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to module.id,
            "tenantId" to module.tenant?.id,
            "name" to module.name,
            "description" to module.description,
            "moduleType" to module.moduleType?.name,
            "isActive" to module.isActive,
            "createdAt" to module.createdAt
        )
        val versions = em.createQuery(
            "SELECT v FROM ModuleVersion v WHERE v.module.id = :moduleId ORDER BY v.version DESC",
            ModuleVersion::class.java
        )
            .setParameter("moduleId", module.id)
            .resultList
        base["versionCount"] = versions.size
        if (includeVersions) {
            base["versions"] = versions.map { versionToMap(it) }
        }
        return base
    }
    
    @GET
    fun list(@QueryParam("tenantId") tenantId: String, @QueryParam("type") type: String?): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantUuid = try {
            UUID.fromString(tenantId)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
        }
        if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "module:read")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val parsedType = type?.let { parseModuleType(it) }
        if (type != null && parsedType == null) {
            return Response.status(400).entity(mapOf("error" to "Invalid module type")).build()
        }

        val modules = if (parsedType != null) {
            em.createQuery(
                "SELECT m FROM CodeModule m WHERE m.tenant.id = :tenantId AND m.moduleType = :moduleType",
                CodeModule::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .setParameter("moduleType", parsedType)
                .resultList
        } else {
            em.createQuery(
                "SELECT m FROM CodeModule m WHERE m.tenant.id = :tenantId",
                CodeModule::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .resultList
        }

        return Response.ok(modules.map { moduleToMap(it, includeVersions = false) }).build()
    }
    
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        val moduleType = request["moduleType"] as? String ?: return Response.status(400).entity(mapOf("error" to "Module type is required")).build()
        val parsedModuleType = parseModuleType(moduleType)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid module type")).build()
        
        if (tenantId == null) {
            return Response.status(400).entity(mapOf("error" to "Tenant ID is required")).build()
        }

        val tenantUuid = try {
            UUID.fromString(tenantId)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
        }
        
        val tenant = em.find(Tenant::class.java, tenantUuid)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "module:write")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        
        val module = CodeModule().apply {
            this.name = name
            this.tenant = tenant
            this.description = request["description"] as? String
            this.moduleType = parsedModuleType
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
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val moduleId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid module id")).build()
        }
        val module = em.find(CodeModule::class.java, moduleId)
        return if (module != null) {
            val moduleTenantId = module.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Module tenant not found")).build()
            if (!SecurityUtils.canAccessTenant(currentUser, moduleTenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "module:read")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            Response.ok(moduleToMap(module, includeVersions = true)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Module not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val moduleId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid module id")).build()
        }
        val module = em.find(CodeModule::class.java, moduleId)
        return if (module != null) {
            val moduleTenantId = module.tenant?.id?.toString()
                ?: return Response.status(400).entity(mapOf("error" to "Module tenant not found")).build()
            if (!SecurityUtils.canAccessTenant(currentUser, moduleTenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "module:delete")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            module.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Module not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/versions")
    fun getVersions(@PathParam("id") id: String): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val moduleId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid module id")).build()
        }
        val module = em.find(CodeModule::class.java, moduleId)
            ?: return Response.status(404).entity(mapOf("error" to "Module not found")).build()
        val moduleTenantId = module.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Module tenant not found")).build()
        if (!SecurityUtils.canAccessTenant(currentUser, moduleTenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "module:read")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        val versions = em.createQuery(
            "SELECT v FROM ModuleVersion v WHERE v.module.id = :moduleId ORDER BY v.version DESC",
            ModuleVersion::class.java
        )
            .setParameter("moduleId", moduleId)
            .resultList
        return Response.ok(versions.map { versionToMap(it) }).build()
    }
    
    @POST
    @Path("/{id}/versions")
    @Transactional
    fun createVersion(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val currentUser = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val moduleId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid module id")).build()
        }
        val module = em.find(CodeModule::class.java, moduleId)
            ?: return Response.status(404).entity(mapOf("error" to "Module not found")).build()
        val moduleTenantId = module.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Module tenant not found")).build()
        if (!SecurityUtils.canAccessTenant(currentUser, moduleTenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "module:write")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        
        val code = request["code"] as? String ?: return Response.status(400).entity(mapOf("error" to "Code is required")).build()
        
        val latestVersion = em.createQuery(
            "SELECT COALESCE(MAX(v.version), 0) FROM ModuleVersion v WHERE v.module.id = :moduleId",
            java.lang.Integer::class.java
        )
            .setParameter("moduleId", moduleId)
            .singleResult
            .toInt()
        
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
