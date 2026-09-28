package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.grafana.GrafanaSyncStatus
import com.dataturntable.grafana.GrafanaTargetInstance
import com.dataturntable.security.SensitiveDataRedactor
import com.dataturntable.service.GrafanaProvisioningService
import com.dataturntable.service.GrafanaSyncService
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

@Path("/api/internal/v1/grafana")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
/**
 * Defines the class `GrafanaSyncResource`, the contract implemented by this source unit.
 */
class GrafanaSyncResource {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var grafanaSyncService: GrafanaSyncService

    @Inject
    lateinit var grafanaProvisioningService: GrafanaProvisioningService

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Implements `currentUser` for this source unit.
     */
    private fun currentUser(): User? =
        try {
            jwt.subject?.let { em.find(User::class.java, UUID.fromString(it)) }
        } catch (_: Exception) {
            null
        }

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun requireGrafanaAdmin(): Response? {
        val user = currentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        if (!user.isSuperAdmin() && !user.hasAbility("tenant:manage")) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        return null
    }

    @GET
    @Path("/sync-states")
    /**
     * Retrieves or enumerates the requested state.
     */
    fun listSyncStates(
        @QueryParam("status") status: String?,
        @QueryParam("target") target: String?,
    ): Response {
        requireGrafanaAdmin()?.let { return it }
        val syncStatus = status?.let {
            try {
                GrafanaSyncStatus.valueOf(it.trim().uppercase())
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid status")).build()
            }
        }
        val targetInstance = target?.let {
            try {
                GrafanaTargetInstance.valueOf(it.trim().uppercase())
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid target")).build()
            }
        }

        return Response.ok(
            grafanaSyncService.listStates(syncStatus, targetInstance).map { state ->
                mapOf(
                    "id" to state.id,
                    "targetInstance" to state.targetInstance.name,
                    "scopeType" to state.scopeType.name,
                    "scopeId" to state.scopeId,
                    "tenantId" to state.tenantId,
                    "userId" to state.userId,
                    "membershipId" to state.membershipId,
                    "syncStatus" to state.syncStatus.name,
                    "desiredState" to state.desiredState,
                    "lastSyncedAt" to state.lastSyncedAt,
                    "lastError" to redactor.text(state.lastError),
                    "updatedAt" to state.updatedAt
                )
            }
        ).build()
    }

    @POST
    @Path("/reconcile")
    @Transactional
    /**
     * Implements `reconcile` for this source unit.
     */
    fun reconcile(): Response {
        requireGrafanaAdmin()?.let { return it }
        return Response.ok(grafanaSyncService.reconcileAll()).build()
    }

    @POST
    @Path("/apply-pending")
    @Transactional
    /**
     * Implements `applyPending` for this source unit.
     */
    fun applyPending(
        @QueryParam("limit") limit: Int?,
        @QueryParam("target") target: String?,
    ): Response {
        requireGrafanaAdmin()?.let { return it }
        val targetInstance = target?.let {
            try {
                GrafanaTargetInstance.valueOf(it.trim().uppercase())
            } catch (_: Exception) {
                return Response.status(400).entity(mapOf("error" to "Invalid target")).build()
            }
        }
        val summary = grafanaProvisioningService.applyPending(limit ?: 50, targetInstance)
        return Response.ok(
            mapOf(
                "processed" to summary.processed,
                "synced" to summary.synced,
                "failed" to summary.failed,
                "skipped" to summary.skipped
            )
        ).build()
    }

    @POST
    @Path("/sync-states/{id}/mark-synced")
    @Transactional
    /**
     * Implements `markSynced` for this source unit.
     */
    fun markSynced(@PathParam("id") id: String): Response {
        requireGrafanaAdmin()?.let { return it }
        val stateId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid id")).build()
        }
        val state = grafanaSyncService.markSynced(stateId)
            ?: return Response.status(404).entity(mapOf("error" to "Sync state not found")).build()
        return Response.ok(mapOf("id" to state.id, "syncStatus" to state.syncStatus.name, "lastSyncedAt" to state.lastSyncedAt)).build()
    }

    @POST
    @Path("/sync-states/{id}/mark-failed")
    @Transactional
    /**
     * Implements `markFailed` for this source unit.
     */
    fun markFailed(@PathParam("id") id: String, request: Map<String, Any>?): Response {
        requireGrafanaAdmin()?.let { return it }
        val stateId = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Invalid id")).build()
        }
        val state = grafanaSyncService.markFailed(stateId, request?.get("error")?.toString())
            ?: return Response.status(404).entity(mapOf("error" to "Sync state not found")).build()
        return Response.ok(mapOf("id" to state.id, "syncStatus" to state.syncStatus.name, "lastError" to redactor.text(state.lastError))).build()
    }
}
