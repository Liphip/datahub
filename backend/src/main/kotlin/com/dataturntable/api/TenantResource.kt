package com.dataturntable.api

import com.dataturntable.tenant.Tenant
import com.dataturntable.auth.User
import com.dataturntable.service.TenantService
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class TenantResource {
    companion object {
        private data class LineageDefaultsDiagnostics(
            val get200Count: AtomicLong = AtomicLong(0),
            val get304Count: AtomicLong = AtomicLong(0),
            val patchSuccessCount: AtomicLong = AtomicLong(0),
            val patchConflictCount: AtomicLong = AtomicLong(0),
            val lastUpdatedEpochMs: AtomicLong = AtomicLong(0)
        )

        private val lineageDefaultsDiagnosticsByTenant = ConcurrentHashMap<UUID, LineageDefaultsDiagnostics>()
    }

    
    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var tenantService: TenantService

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    private fun getCurrentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    private fun requireTenantManager(): Pair<User?, Response?> {
        val user = getCurrentUser()
            ?: return Pair(null, Response.status(401).entity(mapOf("error" to "Unauthorized")).build())
        if (!user.isSuperAdmin() && !user.hasAbility("manage:tenant")) {
            return Pair(null, Response.status(403).entity(mapOf("error" to "Forbidden")).build())
        }
        return Pair(user, null)
    }

    private fun canManageTenant(user: User, tenantId: UUID): Boolean {
        if (user.isSuperAdmin()) return true
        return user.hasAbility("manage:tenant") && user.canAccessTenant(tenantId)
    }

    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
    }

    private fun parseSettings(settingsRaw: String?): MutableMap<String, Any?> {
        if (settingsRaw.isNullOrBlank()) return mutableMapOf()
        return try {
            @Suppress("UNCHECKED_CAST")
            (objectMapper.readValue(settingsRaw, Map::class.java) as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun normalizeLineageDefaults(raw: Map<String, Any?>?): Map<String, Any?> {
        val safeScopePolicy = when ((raw?.get("safeScopePolicy") as? String)?.trim()?.uppercase()) {
            "RUN" -> "RUN"
            "PIPELINE" -> "PIPELINE"
            else -> "AUTO"
        }
        val defaultFetchMode = when ((raw?.get("defaultFetchMode") as? String)?.trim()?.uppercase()) {
            "DETAILS" -> "DETAILS"
            else -> "SUMMARY"
        }
        val defaultDepth = ((raw?.get("defaultDepth") as? Number)?.toInt() ?: 20).coerceIn(1, 200)
        val maxDepthCap = ((raw?.get("maxDepthCap") as? Number)?.toInt() ?: 200).coerceIn(20, 200)
        return mapOf(
            "safeScopePolicy" to safeScopePolicy,
            "defaultFetchMode" to defaultFetchMode,
            "defaultDepth" to defaultDepth,
            "maxDepthCap" to maxDepthCap
        )
    }

    private fun extractLineageDefaults(settingsRaw: String?): Map<String, Any?> {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val lineageDefaultsRaw = settings["lineageDefaults"] as? Map<String, Any?>
        return normalizeLineageDefaults(lineageDefaultsRaw)
    }

    private fun extractLineageDefaultsHistory(settingsRaw: String?): List<Map<String, Any?>> {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val historyRaw = settings["lineageDefaultsHistory"] as? List<Map<String, Any?>> ?: return emptyList()
        return historyRaw.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val defaultsRaw = entry["defaults"] as? Map<String, Any?>
            val changedAt = entry["changedAt"] as? String
            if (changedAt.isNullOrBlank() || defaultsRaw == null) return@mapNotNull null
            mapOf(
                "changedAt" to changedAt,
                "changedByUserId" to (entry["changedByUserId"] as? String),
                "changedByEmail" to (entry["changedByEmail"] as? String),
                "changedFields" to ((entry["changedFields"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList<String>()),
                "defaults" to normalizeLineageDefaults(defaultsRaw)
            )
        }
    }

    private fun lineageDefaultsChangedFields(previous: Map<String, Any?>, next: Map<String, Any?>): List<String> {
        val fields = listOf("safeScopePolicy", "defaultFetchMode", "defaultDepth", "maxDepthCap")
        return fields.filter { key -> previous[key] != next[key] }
    }

    private fun buildLineageDefaultsResponse(settingsRaw: String?, version: String?): Map<String, Any?> {
        val defaults = extractLineageDefaults(settingsRaw)
        val history = extractLineageDefaultsHistory(settingsRaw)
        return defaults + mapOf(
            "version" to version,
            "history" to history
        )
    }

    private fun lineageDefaultsEtag(version: String): String = "W/\"$version\""

    private fun lineageDefaultsDiagnostics(tenantId: UUID): LineageDefaultsDiagnostics =
        lineageDefaultsDiagnosticsByTenant.computeIfAbsent(tenantId) { LineageDefaultsDiagnostics() }

    private fun markLineageDefaultsDiagnostics(tenantId: UUID, action: String) {
        val diagnostics = lineageDefaultsDiagnostics(tenantId)
        when (action) {
            "GET_200" -> diagnostics.get200Count.incrementAndGet()
            "GET_304" -> diagnostics.get304Count.incrementAndGet()
            "PATCH_200" -> diagnostics.patchSuccessCount.incrementAndGet()
            "PATCH_409" -> diagnostics.patchConflictCount.incrementAndGet()
        }
        diagnostics.lastUpdatedEpochMs.set(System.currentTimeMillis())
    }

    private fun lineageDefaultsDiagnosticsSnapshot(tenantId: UUID): Map<String, Any?> {
        val diagnostics = lineageDefaultsDiagnostics(tenantId)
        val get200 = diagnostics.get200Count.get()
        val get304 = diagnostics.get304Count.get()
        val patchSuccess = diagnostics.patchSuccessCount.get()
        val patchConflict = diagnostics.patchConflictCount.get()
        val readTotal = get200 + get304
        val patchTotal = patchSuccess + patchConflict
        val readNotModifiedRate = if (readTotal > 0) get304.toDouble() / readTotal.toDouble() else 0.0
        val patchConflictRate = if (patchTotal > 0) patchConflict.toDouble() / patchTotal.toDouble() else 0.0
        val lastUpdated = diagnostics.lastUpdatedEpochMs.get().takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }
        return mapOf(
            "tenantId" to tenantId,
            "get200Count" to get200,
            "get304Count" to get304,
            "patchSuccessCount" to patchSuccess,
            "patchConflictCount" to patchConflict,
            "readTotalCount" to readTotal,
            "readNotModifiedRate" to readNotModifiedRate,
            "patchConflictRate" to patchConflictRate,
            "lastUpdatedAt" to lastUpdated
        )
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

    private fun buildTenantForest(tenants: List<Tenant>, query: String?): List<Map<String, Any?>> {
        val nodesByParentId = tenants.groupBy { it.parent?.id }
        val visibleIds = tenants.mapNotNull { it.id }.toSet()
        val normalizedQuery = query?.trim()?.lowercase()

        fun buildNode(tenant: Tenant): Map<String, Any?>? {
            val children = (nodesByParentId[tenant.id] ?: emptyList()).mapNotNull { buildNode(it) }
            val matchesSelf = normalizedQuery.isNullOrBlank() || tenant.name?.lowercase()?.contains(normalizedQuery) == true

            return if (!matchesSelf && children.isEmpty()) {
                null
            } else {
                toTenantMap(tenant, children)
            }
        }

        val roots = tenants.filter { tenant ->
            val parentId = tenant.parent?.id
            parentId == null || parentId !in visibleIds
        }
        return roots.mapNotNull { buildNode(it) }
    }

    private fun getVisibleTenantsForUser(user: User): List<Tenant> {
        val allTenants = em.createQuery("SELECT t FROM Tenant t", Tenant::class.java).resultList
        if (user.isSuperAdmin()) return allTenants
        return allTenants.filter { tenant ->
            tenant.id?.let { user.canAccessTenant(it) } == true
        }
    }
    
    @GET
    fun list(@QueryParam("recursive") recursive: String?, @QueryParam("query") query: String?): Response {
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        val visibleTenants = getVisibleTenantsForUser(user)

        if (recursive.equals("true", ignoreCase = true)) {
            return Response.ok(buildTenantForest(visibleTenants, query)).build()
        }

        val visibleTenantIds = visibleTenants.mapNotNull { it.id }.toSet()
        val tenants = visibleTenants.filter { tenant ->
            val parentId = tenant.parent?.id
            parentId == null || parentId !in visibleTenantIds
        }
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
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val name = request["name"] as? String ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        
        val parentId = request["parentId"] as? String
        val parent = try {
            parentId?.let { em.find(Tenant::class.java, parseUuid(it, "parentId")) }
        } catch (e: WebApplicationException) {
            return e.response
        }
        
        if (parent == null) {
            if (!user.isSuperAdmin()) {
                return Response.status(403).entity(mapOf("error" to "Only superadmin can create root tenants")).build()
            }
            val rootTenants = em.createQuery("SELECT t FROM Tenant t WHERE t.parent IS NULL", Tenant::class.java).resultList
            if (rootTenants.isNotEmpty()) {
                return Response.status(400).entity(mapOf("error" to "Root tenant already exists. Create a sub-tenant instead.")).build()
            }
        } else {
            val parentTenantId = parent.id ?: return Response.status(400).entity(mapOf("error" to "Invalid parentId")).build()
            if (!canManageTenant(user, parentTenantId)) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
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
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
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
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
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
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        val tenant = em.find(Tenant::class.java, tenantId)
        return if (tenant != null) {
            if (tenant.parent == null && !user.isSuperAdmin()) {
                return Response.status(403).entity(mapOf("error" to "Only superadmin can delete root tenants")).build()
            }
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
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val parentId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, parentId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val children = em.createQuery("SELECT t FROM Tenant t WHERE t.parent.id = :parentId", Tenant::class.java)
            .setParameter("parentId", parentId)
            .resultList
        val visibleChildren = if (user.isSuperAdmin()) {
            children
        } else {
            children.filter { child -> child.id?.let { user.canAccessTenant(it) } == true }
        }
        return Response.ok(visibleChildren.map { toTenantMap(it) }).build()
    }

    @GET
    @Path("/{id}/lineage-defaults")
    fun getLineageDefaults(
        @PathParam("id") id: String,
        @HeaderParam("If-None-Match") ifNoneMatch: String?
    ): Response {
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val tenant = em.find(Tenant::class.java, tenantId)
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        val currentVersion = tenant.updatedAt.toString()
        val etag = lineageDefaultsEtag(currentVersion)
        val matches = ifNoneMatch?.contains(currentVersion) == true || ifNoneMatch?.contains(etag) == true
        if (matches) {
            markLineageDefaultsDiagnostics(tenantId, "GET_304")
            return Response.notModified()
                .header("ETag", etag)
                .build()
        }
        markLineageDefaultsDiagnostics(tenantId, "GET_200")
        return Response.ok(buildLineageDefaultsResponse(tenant.settings, currentVersion))
            .header("ETag", etag)
            .build()
    }

    @GET
    @Path("/{id}/lineage-defaults/diagnostics")
    fun getLineageDefaultsDiagnostics(@PathParam("id") id: String): Response {
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val tenant = em.find(Tenant::class.java, tenantId)
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        return Response.ok(lineageDefaultsDiagnosticsSnapshot(tenant.id ?: tenantId)).build()
    }

    @PATCH
    @Path("/{id}/lineage-defaults")
    @Transactional
    fun updateLineageDefaults(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val (currentUser, denial) = requireTenantManager()
        denial?.let { return it }
        val user = currentUser ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()

        val tenantId = try {
            parseUuid(id, "tenant id")
        } catch (e: WebApplicationException) {
            return e.response
        }
        if (!canManageTenant(user, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val tenant = em.find(Tenant::class.java, tenantId)
            ?: return Response.status(404).entity(mapOf("error" to "Tenant not found")).build()

        val ifVersion = request["ifVersion"] as? String
        val currentVersion = tenant.updatedAt.toString()
        if (!ifVersion.isNullOrBlank() && ifVersion != currentVersion) {
            markLineageDefaultsDiagnostics(tenantId, "PATCH_409")
            return Response.status(409)
                .entity(mapOf(
                    "error" to "Lineage defaults were updated by another user. Refresh and retry.",
                    "currentVersion" to currentVersion
                ))
                .build()
        }

        val currentLineageDefaults = extractLineageDefaults(tenant.settings).toMutableMap()
        val merged = currentLineageDefaults.toMutableMap()
        request["safeScopePolicy"]?.let {
            val value = (it as? String)?.trim()?.uppercase()
            if (value !in setOf("AUTO", "RUN", "PIPELINE")) {
                return Response.status(400).entity(mapOf("error" to "Invalid safeScopePolicy. Allowed values: AUTO, RUN, PIPELINE")).build()
            }
            merged["safeScopePolicy"] = value
        }
        request["defaultFetchMode"]?.let {
            val value = (it as? String)?.trim()?.uppercase()
            if (value !in setOf("SUMMARY", "DETAILS")) {
                return Response.status(400).entity(mapOf("error" to "Invalid defaultFetchMode. Allowed values: SUMMARY, DETAILS")).build()
            }
            merged["defaultFetchMode"] = value
        }
        request["defaultDepth"]?.let {
            val value = (it as? Number)?.toInt() ?: return Response.status(400).entity(mapOf("error" to "defaultDepth must be a number")).build()
            if (value !in 1..200) {
                return Response.status(400).entity(mapOf("error" to "defaultDepth must be between 1 and 200")).build()
            }
            merged["defaultDepth"] = value
        }
        request["maxDepthCap"]?.let {
            val value = (it as? Number)?.toInt() ?: return Response.status(400).entity(mapOf("error" to "maxDepthCap must be a number")).build()
            if (value !in 20..200) {
                return Response.status(400).entity(mapOf("error" to "maxDepthCap must be between 20 and 200")).build()
            }
            merged["maxDepthCap"] = value
        }

        val settings = parseSettings(tenant.settings)
        val normalizedDefaults = normalizeLineageDefaults(merged)
        val previousDefaults = extractLineageDefaults(tenant.settings)
        val changedFields = lineageDefaultsChangedFields(previousDefaults, normalizedDefaults)
        val currentHistory = extractLineageDefaultsHistory(tenant.settings).toMutableList()
        if (changedFields.isNotEmpty()) {
            currentHistory.add(
                mapOf(
                    "changedAt" to java.time.Instant.now().toString(),
                    "changedByUserId" to user.id?.toString(),
                    "changedByEmail" to user.email,
                    "changedFields" to changedFields,
                    "defaults" to normalizedDefaults
                )
            )
        }
        settings["lineageDefaults"] = normalizedDefaults
        settings["lineageDefaultsHistory"] = currentHistory.takeLast(20)
        tenant.settings = objectMapper.writeValueAsString(settings)
        tenant.updatedAt = java.time.Instant.now()
        em.merge(tenant)

        val updatedVersion = tenant.updatedAt.toString()
        markLineageDefaultsDiagnostics(tenantId, "PATCH_200")
        return Response.ok(buildLineageDefaultsResponse(tenant.settings, updatedVersion))
            .header("ETag", lineageDefaultsEtag(updatedVersion))
            .build()
    }
}
