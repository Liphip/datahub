package com.dataturntable.service

import com.dataturntable.auth.User
import com.dataturntable.grafana.GrafanaSyncScopeType
import com.dataturntable.grafana.GrafanaSyncState
import com.dataturntable.grafana.GrafanaSyncStatus
import com.dataturntable.grafana.GrafanaTargetInstance
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

@ApplicationScoped
/**
 * Defines the class `GrafanaSyncService`, the contract implemented by this source unit.
 */
class GrafanaSyncService {

    @PersistenceContext
    lateinit var em: EntityManager

    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    /**
     * Retrieves or enumerates the requested state.
     */
    fun listStates(status: GrafanaSyncStatus?, target: GrafanaTargetInstance?): List<GrafanaSyncState> {
        val conditions = mutableListOf<String>()
        if (status != null) conditions += "g.syncStatus = :status"
        if (target != null) conditions += "g.targetInstance = :target"
        val jpql = buildString {
            append("SELECT g FROM GrafanaSyncState g")
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY g.updatedAt DESC")
        }
        val query = em.createQuery(jpql, GrafanaSyncState::class.java)
        if (status != null) query.setParameter("status", status)
        if (target != null) query.setParameter("target", target)
        return query.resultList
    }

    /**
     * Retrieves or enumerates the requested state.
     */
    fun listPendingStates(limit: Int = 50, target: GrafanaTargetInstance? = null): List<GrafanaSyncState> {
        val conditions = mutableListOf("g.syncStatus = :status")
        if (target != null) conditions += "g.targetInstance = :target"
        val jpql = buildString {
            append("SELECT g FROM GrafanaSyncState g WHERE ")
            append(conditions.joinToString(" AND "))
            append(" ORDER BY g.updatedAt ASC")
        }
        val query = em.createQuery(jpql, GrafanaSyncState::class.java)
            .setParameter("status", GrafanaSyncStatus.PENDING)
            .setMaxResults(limit.coerceIn(1, 500))
        if (target != null) query.setParameter("target", target)
        return query.resultList
    }

    @Transactional
    /**
     * Implements `reconcileAll` for this source unit.
     */
    fun reconcileAll(): Map<String, Int> {
        val tenants = em.createQuery("SELECT t FROM Tenant t", Tenant::class.java).resultList
        val users = em.createQuery("SELECT u FROM User u", User::class.java).resultList
        val memberships = em.createQuery("SELECT m FROM Membership m", Membership::class.java).resultList

        tenants.forEach { queueTenantSync(it) }
        users.forEach { queueUserSync(it) }
        memberships.forEach { queueMembershipSync(it) }

        return mapOf(
            "tenantsQueued" to tenants.size,
            "usersQueued" to users.size,
            "membershipsQueued" to memberships.size
        )
    }

    @Transactional
    /**
     * Implements `queueTenantSync` for this source unit.
     */
    fun queueTenantSync(tenant: Tenant) {
        val tenantId = tenant.id ?: return
        upsertState(
            target = GrafanaTargetInstance.DASHBOARD,
            scopeType = GrafanaSyncScopeType.TENANT,
            scopeId = tenantId,
            tenantId = tenantId,
            userId = null,
            membershipId = null,
            desiredState = buildTenantDesiredState(tenant)
        )
    }

    @Transactional
    /**
     * Implements `queueTenantDeletion` for this source unit.
     */
    fun queueTenantDeletion(tenantId: UUID, tenantName: String? = null) {
        upsertState(
            target = GrafanaTargetInstance.DASHBOARD,
            scopeType = GrafanaSyncScopeType.TENANT,
            scopeId = tenantId,
            tenantId = tenantId,
            userId = null,
            membershipId = null,
            desiredState = mapOf(
                "deleted" to true,
                "tenantId" to tenantId.toString(),
                "name" to tenantName,
                "dashboardGrafana" to mapOf(
                    "organizationName" to tenantName
                )
            )
        )
    }

    @Transactional
    /**
     * Implements `queueUserSync` for this source unit.
     */
    fun queueUserSync(user: User) {
        val userId = user.id ?: return
        upsertState(
            target = GrafanaTargetInstance.DASHBOARD,
            scopeType = GrafanaSyncScopeType.USER,
            scopeId = userId,
            tenantId = null,
            userId = userId,
            membershipId = null,
            desiredState = buildDashboardUserDesiredState(user)
        )
        upsertState(
            target = GrafanaTargetInstance.MONITORING,
            scopeType = GrafanaSyncScopeType.USER,
            scopeId = userId,
            tenantId = null,
            userId = userId,
            membershipId = null,
            desiredState = buildMonitoringUserDesiredState(user)
        )
    }

    @Transactional
    /**
     * Implements `queueUserDeletion` for this source unit.
     */
    fun queueUserDeletion(
        userId: UUID,
        email: String? = null,
        firstName: String? = null,
        lastName: String? = null,
    ) {
        val deletionState = mapOf(
            "deleted" to true,
            "userId" to userId.toString(),
            "email" to email,
            "firstName" to firstName,
            "lastName" to lastName
        )
        upsertState(GrafanaTargetInstance.DASHBOARD, GrafanaSyncScopeType.USER, userId, null, userId, null, deletionState)
        upsertState(GrafanaTargetInstance.MONITORING, GrafanaSyncScopeType.USER, userId, null, userId, null, deletionState)
    }

    @Transactional
    /**
     * Implements `queueMembershipSync` for this source unit.
     */
    fun queueMembershipSync(membership: Membership) {
        val membershipId = membership.id ?: return
        val tenantId = membership.tenant?.id ?: return
        val userId = membership.authUser?.id ?: return
        upsertState(
            target = GrafanaTargetInstance.DASHBOARD,
            scopeType = GrafanaSyncScopeType.MEMBERSHIP,
            scopeId = membershipId,
            tenantId = tenantId,
            userId = userId,
            membershipId = membershipId,
            desiredState = buildMembershipDesiredState(membership)
        )
    }

    @Transactional
    /**
     * Implements `queueMembershipDeletion` for this source unit.
     */
    fun queueMembershipDeletion(
        membershipId: UUID,
        tenantId: UUID?,
        userId: UUID?,
        tenantName: String? = null,
        userEmail: String? = null,
    ) {
        upsertState(
            target = GrafanaTargetInstance.DASHBOARD,
            scopeType = GrafanaSyncScopeType.MEMBERSHIP,
            scopeId = membershipId,
            tenantId = tenantId,
            userId = userId,
            membershipId = membershipId,
            desiredState = mapOf(
                "deleted" to true,
                "membershipId" to membershipId.toString(),
                "tenantId" to tenantId?.toString(),
                "userId" to userId?.toString(),
                "tenantName" to tenantName,
                "userEmail" to userEmail
            )
        )
    }

    @Transactional
    /**
     * Implements `markSynced` for this source unit.
     */
    fun markSynced(id: UUID): GrafanaSyncState? {
        val state = GrafanaSyncState.findById(id) ?: return null
        state.syncStatus = GrafanaSyncStatus.SYNCED
        state.lastSyncedAt = Instant.now()
        state.lastError = null
        state.updatedAt = Instant.now()
        em.merge(state)
        return state
    }

    @Transactional
    /**
     * Implements `markFailed` for this source unit.
     */
    fun markFailed(id: UUID, error: String?): GrafanaSyncState? {
        val state = GrafanaSyncState.findById(id) ?: return null
        state.syncStatus = GrafanaSyncStatus.FAILED
        state.lastError = error?.take(4000)
        state.updatedAt = Instant.now()
        em.merge(state)
        return state
    }

    /**
     * Implements `upsertState` for this source unit.
     */
    private fun upsertState(
        target: GrafanaTargetInstance,
        scopeType: GrafanaSyncScopeType,
        scopeId: UUID,
        tenantId: UUID?,
        userId: UUID?,
        membershipId: UUID?,
        desiredState: Map<String, Any?>
    ) {
        val state = GrafanaSyncState.findByTargetAndScope(target, scopeType, scopeId) ?: GrafanaSyncState().apply {
            targetInstance = target
            this.scopeType = scopeType
            this.scopeId = scopeId
            createdAt = Instant.now()
        }
        state.tenantId = tenantId
        state.userId = userId
        state.membershipId = membershipId
        state.desiredState = objectMapper.writeValueAsString(desiredState)
        state.syncStatus = GrafanaSyncStatus.PENDING
        state.updatedAt = Instant.now()
        if (state.id == null) {
            em.persist(state)
        } else {
            em.merge(state)
        }
    }

    /**
     * Implements `buildTenantDesiredState` for this source unit.
     */
    private fun buildTenantDesiredState(tenant: Tenant): Map<String, Any?> =
        mapOf(
            "tenantId" to tenant.id?.toString(),
            "name" to tenant.name,
            "slug" to slugify(tenant.name ?: tenant.id.toString()),
            "parentTenantId" to tenant.parent?.id?.toString(),
            "pipelineSchema" to "tenant_${tenant.id.toString().replace("-", "_")}",
            "dashboardGrafana" to mapOf(
                "organizationName" to (tenant.name ?: "tenant-${tenant.id}"),
                "organizationRoleModel" to "tenant-mirrored",
                "datasourceName" to "tenant-${slugify(tenant.name ?: tenant.id.toString())}-data"
            )
        )

    /**
     * Implements `buildDashboardUserDesiredState` for this source unit.
     */
    private fun buildDashboardUserDesiredState(user: User): Map<String, Any?> =
        mapOf(
            "userId" to user.id?.toString(),
            "email" to user.email,
            "firstName" to user.firstName,
            "lastName" to user.lastName,
            "isActive" to user.isActive,
            "dashboardGrafanaAdmin" to user.isSuperAdmin()
        )

    /**
     * Implements `buildMonitoringUserDesiredState` for this source unit.
     */
    private fun buildMonitoringUserDesiredState(user: User): Map<String, Any?> =
        mapOf(
            "userId" to user.id?.toString(),
            "email" to user.email,
            "isActive" to user.isActive,
            "monitoringGrafanaAccess" to user.isSuperAdmin(),
            "monitoringGrafanaAdmin" to user.isSuperAdmin()
        )

    /**
     * Implements `buildMembershipDesiredState` for this source unit.
     */
    private fun buildMembershipDesiredState(membership: Membership): Map<String, Any?> {
        val user = membership.authUser
        val tenant = membership.tenant
        return mapOf(
            // Independent Grafana sessions cannot enforce interval boundaries yet (IAM-09).
            // Do not provision a persistent external grant from a bounded platform membership.
            "deleted" to (membership.validFrom != null || membership.validUntil != null),
            "membershipId" to membership.id?.toString(),
            "tenantId" to tenant?.id?.toString(),
            "tenantName" to tenant?.name,
            "userId" to user?.id?.toString(),
            "userEmail" to user?.email,
            "roleId" to membership.role?.id?.toString(),
            "roleName" to membership.role?.name,
            "grafanaRole" to resolveGrafanaRole(user, membership),
            "monitoringGrafanaAccess" to (user?.isSuperAdmin() == true)
        )
    }

    /**
     * Implements `resolveGrafanaRole` for this source unit.
     */
    private fun resolveGrafanaRole(user: User?, membership: Membership): String {
        if (user?.isSuperAdmin() == true) return "Admin"
        val roleName = membership.role?.name?.trim()?.lowercase().orEmpty()
        val abilities = buildString {
            append(membership.abilities ?: "")
            append(" ")
            append(membership.role?.abilities ?: "")
        }.lowercase()

        return when {
            roleName.contains("tenant admin") || roleName.contains("tenantadmin") -> "Admin"
            "tenant:manage" in abilities || "user:write" in abilities -> "Admin"
            "module:write" in abilities || "pipeline:write" in abilities || "data:write" in abilities -> "Editor"
            else -> "Viewer"
        }
    }

    /**
     * Implements `slugify` for this source unit.
     */
    private fun slugify(value: String): String =
        value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "tenant" }
}
