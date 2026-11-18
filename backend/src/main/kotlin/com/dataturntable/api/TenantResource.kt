package com.dataturntable.api

import com.dataturntable.tenant.Tenant
import com.dataturntable.audit.AuditService
import com.dataturntable.auth.User
import com.dataturntable.service.GrafanaSyncService
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@Path("/api/internal/v1/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
/**
 * Defines the class `TenantResource`, the contract implemented by this source unit.
 */
class TenantResource {
    companion object {
        /**
         * Defines the class `LineageDefaultsDiagnostics`, the contract implemented by this source unit.
         */
        private data class LineageDefaultsDiagnostics(
            val get200Count: AtomicLong = AtomicLong(0),
            val get304Count: AtomicLong = AtomicLong(0),
            val patchSuccessCount: AtomicLong = AtomicLong(0),
            val patchConflictCount: AtomicLong = AtomicLong(0),
            val get200Events: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue(),
            val get304Events: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue(),
            val patchSuccessEvents: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue(),
            val patchConflictEvents: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue(),
            val lastUpdatedEpochMs: AtomicLong = AtomicLong(0),
            val resetCount: AtomicLong = AtomicLong(0),
            val lastResetEpochMs: AtomicLong = AtomicLong(0)
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
    lateinit var audit: AuditService

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var grafanaSyncService: GrafanaSyncService

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun getCurrentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun requireTenantManager(): Pair<User?, Response?> {
        val user = getCurrentUser()
            ?: return Pair(null, Response.status(401).entity(mapOf("error" to "Unauthorized")).build())
        if (!user.isSuperAdmin() && !user.hasAbility("tenant:manage")) {
            return Pair(null, Response.status(403).entity(mapOf("error" to "Forbidden")).build())
        }
        return Pair(user, null)
    }

    /**
     * Evaluates the named capability or state predicate.
     */
    private fun canManageTenant(user: User, tenantId: UUID): Boolean {
        if (user.isSuperAdmin()) return true
        return user.hasAbility("tenant:manage") && user.canAccessTenant(tenantId)
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseUuid(value: String, field: String): UUID {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "Invalid $field")).build())
        }
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseSettings(settingsRaw: String?): MutableMap<String, Any?> {
        if (settingsRaw.isNullOrBlank()) return mutableMapOf()
        return try {
            @Suppress("UNCHECKED_CAST")
            (objectMapper.readValue(settingsRaw, Map::class.java) as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    /**
     * Validates or normalizes input before the caller continues.
     */
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

    /**
     * Implements `extractLineageDefaults` for this source unit.
     */
    private fun extractLineageDefaults(settingsRaw: String?): Map<String, Any?> {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val lineageDefaultsRaw = settings["lineageDefaults"] as? Map<String, Any?>
        return normalizeLineageDefaults(lineageDefaultsRaw)
    }

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun normalizeLineageDiagnosticsPolicy(raw: Map<String, Any?>?): Map<String, Any> {
        val conflictWatchRate = ((raw?.get("conflictWatchRate") as? Number)?.toDouble() ?: 0.35).coerceIn(0.0, 1.0)
        val requestedHotRate = ((raw?.get("conflictHotRate") as? Number)?.toDouble() ?: 0.50).coerceIn(0.0, 1.0)
        val conflictHotRate = requestedHotRate.coerceAtLeast(conflictWatchRate)
        val conflictTrendDelta = ((raw?.get("conflictTrendDelta") as? Number)?.toDouble() ?: 0.15).coerceIn(0.0, 1.0)
        val minPatchSamplesForHot = ((raw?.get("minPatchSamplesForHot") as? Number)?.toInt() ?: 3).coerceIn(1, 100)
        val pollOverfetchMinReads = ((raw?.get("pollOverfetchMinReads") as? Number)?.toInt() ?: 20).coerceIn(1, 1000)
        val pollOverfetchRate = ((raw?.get("pollOverfetchRate") as? Number)?.toDouble() ?: 0.90).coerceIn(0.0, 1.0)
        return mapOf(
            "conflictWatchRate" to conflictWatchRate,
            "conflictHotRate" to conflictHotRate,
            "conflictTrendDelta" to conflictTrendDelta,
            "minPatchSamplesForHot" to minPatchSamplesForHot,
            "pollOverfetchMinReads" to pollOverfetchMinReads,
            "pollOverfetchRate" to pollOverfetchRate
        )
    }

    /**
     * Implements `extractLineageDiagnosticsPolicy` for this source unit.
     */
    private fun extractLineageDiagnosticsPolicy(settingsRaw: String?): Map<String, Any> {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val policyRaw = settings["lineageDiagnosticsPolicy"] as? Map<String, Any?>
        return normalizeLineageDiagnosticsPolicy(policyRaw)
    }

    /**
     * Implements `extractLineageDiagnosticsPolicyHistory` for this source unit.
     */
    private fun extractLineageDiagnosticsPolicyHistory(settingsRaw: String?): List<Map<String, Any?>> {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val historyRaw = settings["lineageDiagnosticsPolicyHistory"] as? List<Map<String, Any?>> ?: return emptyList()
        return historyRaw.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val policyRaw = entry["policy"] as? Map<String, Any?>
            val changedAt = entry["changedAt"] as? String
            if (changedAt.isNullOrBlank() || policyRaw == null) return@mapNotNull null
            mapOf(
                "changedAt" to changedAt,
                "changedByUserId" to (entry["changedByUserId"] as? String),
                "changedByEmail" to (entry["changedByEmail"] as? String),
                "changedFields" to ((entry["changedFields"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList<String>()),
                "policy" to normalizeLineageDiagnosticsPolicy(policyRaw)
            )
        }
    }

    /**
     * Implements `lineageDiagnosticsPolicyChangedFields` for this source unit.
     */
    private fun lineageDiagnosticsPolicyChangedFields(previous: Map<String, Any>, next: Map<String, Any>): List<String> {
        val fields = listOf(
            "conflictWatchRate",
            "conflictHotRate",
            "conflictTrendDelta",
            "minPatchSamplesForHot",
            "pollOverfetchMinReads",
            "pollOverfetchRate"
        )
        return fields.filter { key -> previous[key] != next[key] }
    }

    /**
     * Implements `buildLineageDiagnosticsPolicyResponse` for this source unit.
     */
    private fun buildLineageDiagnosticsPolicyResponse(settingsRaw: String?, version: String?): Map<String, Any?> {
        val policy = extractLineageDiagnosticsPolicy(settingsRaw)
        val history = extractLineageDiagnosticsPolicyHistory(settingsRaw)
        return policy + mapOf(
            "version" to version,
            "history" to history
        )
    }

    /**
     * Implements `extractLineageDiagnosticsResetHistory` for this source unit.
     */
    private fun extractLineageDiagnosticsResetHistory(settingsRaw: String?): List<Map<String, Any?>> {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val historyRaw = settings["lineageDiagnosticsResetHistory"] as? List<Map<String, Any?>> ?: return emptyList()
        return historyRaw.mapNotNull { entry ->
            val resetAt = entry["resetAt"] as? String
            if (resetAt.isNullOrBlank()) return@mapNotNull null
            mapOf(
                "resetAt" to resetAt,
                "resetByUserId" to (entry["resetByUserId"] as? String),
                "resetByEmail" to (entry["resetByEmail"] as? String),
                "reason" to (entry["reason"] as? String)
            )
        }
    }

    /**
     * Implements `extractLineageDiagnosticsCounters` for this source unit.
     */
    private fun extractLineageDiagnosticsCounters(settingsRaw: String?): Map<String, Any?>? {
        val settings = parseSettings(settingsRaw)
        @Suppress("UNCHECKED_CAST")
        val countersRaw = settings["lineageDiagnosticsCounters"] as? Map<String, Any?> ?: return null
        return countersRaw
    }

    /**
     * Implements `storeLineageDiagnosticsCounters` for this source unit.
     */
    private fun storeLineageDiagnosticsCounters(tenant: Tenant, diagnostics: LineageDefaultsDiagnostics) {
        val settings = parseSettings(tenant.settings)
        val counters = mapOf(
            "get200Count" to diagnostics.get200Count.get(),
            "get304Count" to diagnostics.get304Count.get(),
            "patchSuccessCount" to diagnostics.patchSuccessCount.get(),
            "patchConflictCount" to diagnostics.patchConflictCount.get(),
            "resetCount" to diagnostics.resetCount.get(),
            "lastResetEpochMs" to diagnostics.lastResetEpochMs.get(),
            "lastUpdatedEpochMs" to diagnostics.lastUpdatedEpochMs.get()
        )
        settings["lineageDiagnosticsCounters"] = counters
        tenant.settings = objectMapper.writeValueAsString(settings)
    }

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun loadLineageDiagnosticsCountersFromSettings(tenant: Tenant) {
        val tenantId = tenant.id ?: return
        val countersRaw = extractLineageDiagnosticsCounters(tenant.settings) ?: return
        val diagnostics = lineageDefaultsDiagnosticsByTenant.computeIfAbsent(tenantId) { LineageDefaultsDiagnostics() }
        (countersRaw["get200Count"] as? Number)?.let { diagnostics.get200Count.set(it.toLong()) }
        (countersRaw["get304Count"] as? Number)?.let { diagnostics.get304Count.set(it.toLong()) }
        (countersRaw["patchSuccessCount"] as? Number)?.let { diagnostics.patchSuccessCount.set(it.toLong()) }
        (countersRaw["patchConflictCount"] as? Number)?.let { diagnostics.patchConflictCount.set(it.toLong()) }
        (countersRaw["resetCount"] as? Number)?.let { diagnostics.resetCount.set(it.toLong()) }
        (countersRaw["lastResetEpochMs"] as? Number)?.let { diagnostics.lastResetEpochMs.set(it.toLong()) }
        (countersRaw["lastUpdatedEpochMs"] as? Number)?.let { diagnostics.lastUpdatedEpochMs.set(it.toLong()) }
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseDiagnosticsReset(request: Map<String, Any>): String? {
        val reason = request["reason"] as? String
        return reason?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseDiagnosticsPolicyPatch(request: Map<String, Any>): Map<String, Any> {
        val patch = mutableMapOf<String, Any>()

        request["conflictWatchRate"]?.let {
            val value = (it as? Number)?.toDouble()
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "conflictWatchRate must be a number between 0 and 1")).build())
            if (value !in 0.0..1.0) {
                throw WebApplicationException(Response.status(400).entity(mapOf("error" to "conflictWatchRate must be between 0 and 1")).build())
            }
            patch["conflictWatchRate"] = value
        }

        request["conflictHotRate"]?.let {
            val value = (it as? Number)?.toDouble()
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "conflictHotRate must be a number between 0 and 1")).build())
            if (value !in 0.0..1.0) {
                throw WebApplicationException(Response.status(400).entity(mapOf("error" to "conflictHotRate must be between 0 and 1")).build())
            }
            patch["conflictHotRate"] = value
        }

        request["conflictTrendDelta"]?.let {
            val value = (it as? Number)?.toDouble()
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "conflictTrendDelta must be a number between 0 and 1")).build())
            if (value !in 0.0..1.0) {
                throw WebApplicationException(Response.status(400).entity(mapOf("error" to "conflictTrendDelta must be between 0 and 1")).build())
            }
            patch["conflictTrendDelta"] = value
        }

        request["minPatchSamplesForHot"]?.let {
            val value = (it as? Number)?.toInt()
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "minPatchSamplesForHot must be an integer between 1 and 100")).build())
            if (value !in 1..100) {
                throw WebApplicationException(Response.status(400).entity(mapOf("error" to "minPatchSamplesForHot must be between 1 and 100")).build())
            }
            patch["minPatchSamplesForHot"] = value
        }

        request["pollOverfetchMinReads"]?.let {
            val value = (it as? Number)?.toInt()
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "pollOverfetchMinReads must be an integer between 1 and 1000")).build())
            if (value !in 1..1000) {
                throw WebApplicationException(Response.status(400).entity(mapOf("error" to "pollOverfetchMinReads must be between 1 and 1000")).build())
            }
            patch["pollOverfetchMinReads"] = value
        }

        request["pollOverfetchRate"]?.let {
            val value = (it as? Number)?.toDouble()
                ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "pollOverfetchRate must be a number between 0 and 1")).build())
            if (value !in 0.0..1.0) {
                throw WebApplicationException(Response.status(400).entity(mapOf("error" to "pollOverfetchRate must be between 0 and 1")).build())
            }
            patch["pollOverfetchRate"] = value
        }

        return patch
    }

    /**
     * Implements `extractLineageDefaultsHistory` for this source unit.
     */
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

    /**
     * Implements `lineageDefaultsChangedFields` for this source unit.
     */
    private fun lineageDefaultsChangedFields(previous: Map<String, Any?>, next: Map<String, Any?>): List<String> {
        val fields = listOf("safeScopePolicy", "defaultFetchMode", "defaultDepth", "maxDepthCap")
        return fields.filter { key -> previous[key] != next[key] }
    }

    /**
     * Implements `buildLineageDefaultsResponse` for this source unit.
     */
    private fun buildLineageDefaultsResponse(settingsRaw: String?, version: String?): Map<String, Any?> {
        val defaults = extractLineageDefaults(settingsRaw)
        val history = extractLineageDefaultsHistory(settingsRaw)
        return defaults + mapOf(
            "version" to version,
            "history" to history
        )
    }

    /**
     * Implements `lineageDefaultsEtag` for this source unit.
     */
    private fun lineageDefaultsEtag(version: String): String = "W/\"$version\""

    /**
     * Implements `lineageDefaultsDiagnostics` for this source unit.
     */
    private fun lineageDefaultsDiagnostics(tenantId: UUID): LineageDefaultsDiagnostics =
        lineageDefaultsDiagnosticsByTenant.computeIfAbsent(tenantId) { LineageDefaultsDiagnostics() }

    /**
     * Implements `markLineageDefaultsDiagnostics` for this source unit.
     */
    private fun markLineageDefaultsDiagnostics(tenantId: UUID, action: String) {
        val diagnostics = lineageDefaultsDiagnostics(tenantId)
        val now = System.currentTimeMillis()
        when (action) {
            "GET_200" -> {
                diagnostics.get200Count.incrementAndGet()
                diagnostics.get200Events.add(now)
            }
            "GET_304" -> {
                diagnostics.get304Count.incrementAndGet()
                diagnostics.get304Events.add(now)
            }
            "PATCH_200" -> {
                diagnostics.patchSuccessCount.incrementAndGet()
                diagnostics.patchSuccessEvents.add(now)
            }
            "PATCH_409" -> {
                diagnostics.patchConflictCount.incrementAndGet()
                diagnostics.patchConflictEvents.add(now)
            }
        }
        val cutoff24h = now - (24L * 60L * 60L * 1000L)
        /**
         * Implements `prune` for this source unit.
         */
        fun prune(queue: ConcurrentLinkedQueue<Long>) {
            while (true) {
                val head = queue.peek() ?: break
                if (head >= cutoff24h) break
                queue.poll()
            }
        }
        prune(diagnostics.get200Events)
        prune(diagnostics.get304Events)
        prune(diagnostics.patchSuccessEvents)
        prune(diagnostics.patchConflictEvents)
        diagnostics.lastUpdatedEpochMs.set(now)
    }

    /**
     * Implements `resetLineageDefaultsDiagnostics` for this source unit.
     */
    private fun resetLineageDefaultsDiagnostics(tenantId: UUID, tenant: Tenant? = null) {
        val diagnostics = lineageDefaultsDiagnostics(tenantId)
        val now = System.currentTimeMillis()
        diagnostics.get200Count.set(0)
        diagnostics.get304Count.set(0)
        diagnostics.patchSuccessCount.set(0)
        diagnostics.patchConflictCount.set(0)
        diagnostics.get200Events.clear()
        diagnostics.get304Events.clear()
        diagnostics.patchSuccessEvents.clear()
        diagnostics.patchConflictEvents.clear()
        diagnostics.resetCount.incrementAndGet()
        diagnostics.lastResetEpochMs.set(now)
        diagnostics.lastUpdatedEpochMs.set(now)
        tenant?.let { storeLineageDiagnosticsCounters(it, diagnostics) }
    }

    /**
     * Implements `lineageDefaultsDiagnosticsSnapshot` for this source unit.
     */
    private fun lineageDefaultsDiagnosticsSnapshot(tenant: Tenant): Map<String, Any?> {
        val tenantId = tenant.id ?: return mapOf("error" to "Tenant not found")
        loadLineageDiagnosticsCountersFromSettings(tenant)
        val diagnostics = lineageDefaultsDiagnostics(tenantId)
        val now = System.currentTimeMillis()
        val advisoryPolicy = extractLineageDiagnosticsPolicy(tenant.settings)
        val get200 = diagnostics.get200Count.get()
        val get304 = diagnostics.get304Count.get()
        val patchSuccess = diagnostics.patchSuccessCount.get()
        val patchConflict = diagnostics.patchConflictCount.get()
        val readTotal = get200 + get304
        val patchTotal = patchSuccess + patchConflict
        val readNotModifiedRate = if (readTotal > 0) get304.toDouble() / readTotal.toDouble() else 0.0
        val patchConflictRate = if (patchTotal > 0) patchConflict.toDouble() / patchTotal.toDouble() else 0.0
        val lastUpdated = diagnostics.lastUpdatedEpochMs.get().takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }
        val resetCount = diagnostics.resetCount.get()
        val lastResetAt = diagnostics.lastResetEpochMs.get().takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() }
        val resetHistory = extractLineageDiagnosticsResetHistory(tenant.settings)

        /**
         * Implements `countSince` for this source unit.
         */
        fun countSince(queue: ConcurrentLinkedQueue<Long>, sinceEpochMs: Long): Long {
            return queue.count { it >= sinceEpochMs }.toLong()
        }

        /**
         * Defines the class `WindowSnapshot`, the contract implemented by this source unit.
         */
        data class WindowSnapshot(
            val get200Count: Long,
            val get304Count: Long,
            val patchSuccessCount: Long,
            val patchConflictCount: Long,
            val readTotalCount: Long,
            val readNotModifiedRate: Double,
            val patchConflictRate: Double
        )

        /**
         * Implements `windowSnapshot` for this source unit.
         */
        fun windowSnapshot(hours: Long): WindowSnapshot {
            val since = now - (hours * 60L * 60L * 1000L)
            val wGet200 = countSince(diagnostics.get200Events, since)
            val wGet304 = countSince(diagnostics.get304Events, since)
            val wPatchSuccess = countSince(diagnostics.patchSuccessEvents, since)
            val wPatchConflict = countSince(diagnostics.patchConflictEvents, since)
            val wReadTotal = wGet200 + wGet304
            val wPatchTotal = wPatchSuccess + wPatchConflict
            return WindowSnapshot(
                get200Count = wGet200,
                get304Count = wGet304,
                patchSuccessCount = wPatchSuccess,
                patchConflictCount = wPatchConflict,
                readTotalCount = wReadTotal,
                readNotModifiedRate = if (wReadTotal > 0) wGet304.toDouble() / wReadTotal.toDouble() else 0.0,
                patchConflictRate = if (wPatchTotal > 0) wPatchConflict.toDouble() / wPatchTotal.toDouble() else 0.0
            )
        }

        /**
         * Converts a value into the representation required by its caller.
         */
        fun toWindowMap(snapshot: WindowSnapshot): Map<String, Any> = mapOf(
            "get200Count" to snapshot.get200Count,
            "get304Count" to snapshot.get304Count,
            "patchSuccessCount" to snapshot.patchSuccessCount,
            "patchConflictCount" to snapshot.patchConflictCount,
            "readTotalCount" to snapshot.readTotalCount,
            "readNotModifiedRate" to snapshot.readNotModifiedRate,
            "patchConflictRate" to snapshot.patchConflictRate
        )

        /**
         * Implements `advisorySnapshot` for this source unit.
         */
        fun advisorySnapshot(h1: WindowSnapshot, h24: WindowSnapshot): Map<String, Any> {
            val reasons = mutableListOf<String>()
            val recommendedActions = linkedSetOf<String>()

            val conflictWatchRate = (advisoryPolicy["conflictWatchRate"] as? Number)?.toDouble() ?: 0.35
            val conflictHotRate = (advisoryPolicy["conflictHotRate"] as? Number)?.toDouble() ?: 0.50
            val conflictTrendDelta = (advisoryPolicy["conflictTrendDelta"] as? Number)?.toDouble() ?: 0.15
            val minPatchSamplesForHot = (advisoryPolicy["minPatchSamplesForHot"] as? Number)?.toLong() ?: 3L
            val pollOverfetchMinReads = (advisoryPolicy["pollOverfetchMinReads"] as? Number)?.toLong() ?: 20L
            val pollOverfetchRate = (advisoryPolicy["pollOverfetchRate"] as? Number)?.toDouble() ?: 0.90

            val conflictDelta = h1.patchConflictRate - h24.patchConflictRate
            val elevatedConflict = h1.patchConflictCount >= 1 && h1.patchConflictRate >= conflictWatchRate
            val conflictSampleSize = h1.patchSuccessCount + h1.patchConflictCount
            val conflictSpike = h1.patchConflictCount >= 2 || (conflictSampleSize >= minPatchSamplesForHot && h1.patchConflictRate >= conflictHotRate)
            val conflictTrendingUp = h1.patchConflictCount >= 1 && conflictDelta >= conflictTrendDelta
            val pollOverfetch = h1.readTotalCount >= pollOverfetchMinReads && h1.readNotModifiedRate >= pollOverfetchRate

            if (conflictSpike) {
                reasons.add("CONFLICT_SPIKE")
                recommendedActions.add("COORDINATE_EDITOR_WINDOWS")
                recommendedActions.add("REFRESH_BEFORE_SAVE")
            } else if (elevatedConflict) {
                reasons.add("ELEVATED_CONFLICT_RATE")
                recommendedActions.add("REFRESH_BEFORE_SAVE")
            }

            if (conflictTrendingUp) {
                reasons.add("CONFLICT_TREND_UP")
                recommendedActions.add("CHECK_STALE_POLL_HEALTH")
            }

            if (pollOverfetch) {
                reasons.add("POLL_OVERFETCH")
                recommendedActions.add("INCREASE_POLL_INTERVAL")
            }

            val health = when {
                reasons.contains("CONFLICT_SPIKE") -> "HOT"
                reasons.any { it == "ELEVATED_CONFLICT_RATE" || it == "CONFLICT_TREND_UP" || it == "POLL_OVERFETCH" } -> "WATCH"
                else -> "STABLE"
            }

            if (recommendedActions.isEmpty()) {
                recommendedActions.add("NONE")
            }

            return mapOf(
                "health" to health,
                "reasons" to reasons,
                "recommendedActions" to recommendedActions.toList(),
                "conflictRateDelta" to conflictDelta,
                "policy" to advisoryPolicy
            )
        }

        val h1Snapshot = windowSnapshot(1)
        val h24Snapshot = windowSnapshot(24)

        return mapOf(
            "tenantId" to tenantId,
            "get200Count" to get200,
            "get304Count" to get304,
            "patchSuccessCount" to patchSuccess,
            "patchConflictCount" to patchConflict,
            "readTotalCount" to readTotal,
            "readNotModifiedRate" to readNotModifiedRate,
            "patchConflictRate" to patchConflictRate,
            "resetCount" to resetCount,
            "lastResetAt" to lastResetAt,
            "resetHistory" to resetHistory,
            "lastUpdatedAt" to lastUpdated,
            "windows" to mapOf(
                "h1" to toWindowMap(h1Snapshot),
                "h24" to toWindowMap(h24Snapshot)
            ),
            "advisory" to advisorySnapshot(h1Snapshot, h24Snapshot)
        )
    }

    /**
     * Converts a value into the representation required by its caller.
     */
    private fun toTenantMap(tenant: Tenant, children: List<Map<String, Any?>>? = null): Map<String, Any?> {
        val quota = try {
            if (tenant.quota.isNullOrBlank()) emptyMap()
            else objectMapper.readValue(tenant.quota, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        
        val base = mutableMapOf<String, Any?>(
            "id" to tenant.id,
            "name" to tenant.name,
            "parentId" to tenant.parent?.id,
            "createdAt" to tenant.createdAt,
            "updatedAt" to tenant.updatedAt,
            "quota" to quota
        )
        if (children != null) {
            base["children"] = children
        }
        return base
    }

    /**
     * Implements `buildTenantForest` for this source unit.
     */
    private fun buildTenantForest(tenants: List<Tenant>, query: String?): List<Map<String, Any?>> {
        val nodesByParentId = tenants.groupBy { it.parent?.id }
        val visibleIds = tenants.mapNotNull { it.id }.toSet()
        val normalizedQuery = query?.trim()?.lowercase()

        /**
         * Implements `buildNode` for this source unit.
         */
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

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun getVisibleTenantsForUser(user: User): List<Tenant> {
        val allTenants = em.createQuery("SELECT t FROM Tenant t ORDER BY t.createdAt, t.name", Tenant::class.java).resultList
        if (user.isSuperAdmin()) return allTenants
        return allTenants.filter { tenant ->
            tenant.id?.let { user.canAccessTenant(it) } == true
        }
    }
    
    @GET
    /**
     * Retrieves or enumerates the requested state.
     */
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
    /**
     * Creates or persists the requested state.
     */
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
        grafanaSyncService.queueTenantSync(tenant)
        audit.record(
            actorId = user.id,
            tenantId = tenant.id,
            action = "identity.tenant.created",
            resourceType = "tenant",
            resourceId = tenant.id.toString(),
            metadata = mapOf("outcome" to "succeeded", "parentTenantId" to parent?.id?.toString())
        )
        return Response.status(201).entity(mapOf("id" to tenant.id, "name" to tenant.name)).build()
    }
    
    @GET
    @Path("/{id}")
    /**
     * Retrieves or enumerates the requested state.
     */
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
            Response.ok(toTenantMap(tenant)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    /** Reject misspelled keys, coercion and overflow before changing any tenant state. */
    private fun validatedQuota(value: Any?): String {
        require(value is String) { "quota must be a JSON object encoded as a string" }
        val tree = try { objectMapper.readTree(value) } catch (e: Exception) {
            throw IllegalArgumentException("quota must contain valid JSON")
        }
        require(tree != null && tree.isObject) { "quota must contain a JSON object" }
        val minimums = mapOf(
            "ingestRecordsPerMinute" to 0, "maxConcurrentExecutions" to 1,
            "retentionPipelineRunsDays" to -1, "retentionAlertHistoryDays" to -1,
            "max_users" to 0, "max_pipelines" to 0, "max_storage_mb" to 0
        )
        tree.fields().forEachRemaining { (key, number) ->
            val minimum = minimums[key] ?: throw IllegalArgumentException("Unknown quota key: $key")
            require(number.isIntegralNumber && number.canConvertToInt() && number.intValue() >= minimum) {
                "$key must be an integer between $minimum and ${Int.MAX_VALUE}"
            }
        }
        return objectMapper.writeValueAsString(tree)
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    /**
     * Updates the requested state.
     */
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
            val quota = if (request.containsKey("quota")) {
                try { validatedQuota(request["quota"]) } catch (e: IllegalArgumentException) {
                    return Response.status(400).entity(mapOf("error" to e.message)).build()
                }
            } else null
            request["name"]?.let { tenant.name = it as String }
            request["settings"]?.let { tenant.settings = it as String }
            quota?.let { tenant.quota = it }
            tenant.updatedAt = java.time.Instant.now()
            em.merge(tenant)
            grafanaSyncService.queueTenantSync(tenant)
            audit.record(
                actorId = user.id,
                tenantId = tenantId,
                action = "identity.tenant.updated",
                resourceType = "tenant",
                resourceId = tenant.id.toString(),
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.ok(mapOf("id" to tenant.id, "name" to tenant.name)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    /**
     * Removes, stops, or releases the requested state.
     */
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
            tenant.id?.let { grafanaSyncService.queueTenantDeletion(it, tenant.name) }
            em.remove(tenant)
            audit.record(
                actorId = user.id,
                tenantId = tenantId,
                action = "identity.tenant.deleted",
                resourceType = "tenant",
                resourceId = tenantId.toString(),
                metadata = mapOf("outcome" to "succeeded")
            )
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Tenant not found")).build()
        }
    }
    
    @GET
    @Path("/{id}/children")
    /**
     * Retrieves or enumerates the requested state.
     */
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
    /**
     * Retrieves or enumerates the requested state.
     */
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
    /**
     * Retrieves or enumerates the requested state.
     */
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
        return Response.ok(lineageDefaultsDiagnosticsSnapshot(tenant)).build()
    }

    @POST
    @Path("/{id}/lineage-defaults/diagnostics/reset")
    @Transactional
    /**
     * Implements `resetTenantLineageDefaultsDiagnostics` for this source unit.
     */
    fun resetTenantLineageDefaultsDiagnostics(@PathParam("id") id: String, request: Map<String, Any>?): Response {
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

        val reason = request?.let { parseDiagnosticsReset(it) }
        val settings = parseSettings(tenant.settings)
        val currentResetHistory = extractLineageDiagnosticsResetHistory(tenant.settings).toMutableList()
        currentResetHistory.add(
            mapOf(
                "resetAt" to java.time.Instant.now().toString(),
                "resetByUserId" to user.id?.toString(),
                "resetByEmail" to user.email,
                "reason" to reason
            )
        )
        settings["lineageDiagnosticsResetHistory"] = currentResetHistory.takeLast(20)
        tenant.settings = objectMapper.writeValueAsString(settings)
        tenant.updatedAt = java.time.Instant.now()
        em.merge(tenant)

        resetLineageDefaultsDiagnostics(tenantId, tenant)
        return Response.ok(lineageDefaultsDiagnosticsSnapshot(tenant)).build()
    }

    @GET
    @Path("/{id}/lineage-defaults/diagnostics-policy")
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getLineageDefaultsDiagnosticsPolicy(@PathParam("id") id: String): Response {
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
        return Response.ok(buildLineageDiagnosticsPolicyResponse(tenant.settings, tenant.updatedAt.toString())).build()
    }

    @PATCH
    @Path("/{id}/lineage-defaults/diagnostics-policy")
    @Transactional
    /**
     * Updates the requested state.
     */
    fun updateLineageDefaultsDiagnosticsPolicy(@PathParam("id") id: String, request: Map<String, Any>): Response {
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
            return Response.status(409)
                .entity(mapOf(
                    "error" to "Lineage diagnostics policy was updated by another user. Refresh and retry.",
                    "currentVersion" to currentVersion
                ))
                .build()
        }

        val policyPatch = try {
            parseDiagnosticsPolicyPatch(request)
        } catch (e: WebApplicationException) {
            return e.response
        }

        val settings = parseSettings(tenant.settings)
        val existingPolicy = extractLineageDiagnosticsPolicy(tenant.settings)
        val mergedPolicy = existingPolicy.toMutableMap()
        policyPatch.forEach { (key, value) -> mergedPolicy[key] = value }
        val normalizedPolicy = normalizeLineageDiagnosticsPolicy(mergedPolicy)
        val changedFields = lineageDiagnosticsPolicyChangedFields(existingPolicy, normalizedPolicy)
        val currentHistory = extractLineageDiagnosticsPolicyHistory(tenant.settings).toMutableList()
        if (changedFields.isNotEmpty()) {
            currentHistory.add(
                mapOf(
                    "changedAt" to java.time.Instant.now().toString(),
                    "changedByUserId" to user.id?.toString(),
                    "changedByEmail" to user.email,
                    "changedFields" to changedFields,
                    "policy" to normalizedPolicy
                )
            )
        }
        settings["lineageDiagnosticsPolicy"] = normalizedPolicy
        settings["lineageDiagnosticsPolicyHistory"] = currentHistory.takeLast(20)
        tenant.settings = objectMapper.writeValueAsString(settings)
        tenant.updatedAt = java.time.Instant.now()
        em.merge(tenant)

        val updatedVersion = tenant.updatedAt.toString()
        return Response.ok(buildLineageDiagnosticsPolicyResponse(tenant.settings, updatedVersion)).build()
    }

    @PATCH
    @Path("/{id}/lineage-defaults")
    @Transactional
    /**
     * Updates the requested state.
     */
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
            storeLineageDiagnosticsCounters(tenant, lineageDefaultsDiagnostics(tenantId))
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
        storeLineageDiagnosticsCounters(tenant, lineageDefaultsDiagnostics(tenantId))
        return Response.ok(buildLineageDefaultsResponse(tenant.settings, updatedVersion))
            .header("ETag", lineageDefaultsEtag(updatedVersion))
            .build()
    }
}
