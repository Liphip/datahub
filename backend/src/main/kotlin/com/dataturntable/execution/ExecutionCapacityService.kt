package com.dataturntable.execution

import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID

/**
 * Holds short-lived database locks while reserving execution capacity.  The
 * reservation itself is the durable Execution row, so the backend never runs
 * while the coordination lock is held and all replicas see the same limits.
 */
@ApplicationScoped
class ExecutionCapacityService {
    @PersistenceContext lateinit var em: EntityManager
    @ConfigProperty(name = "dataturntable.execution.max-concurrent-global", defaultValue = "100")
    var globalLimit: Int = 100
    @ConfigProperty(name = "dataturntable.execution.max-concurrent-per-tenant", defaultValue = "10")
    var defaultTenantLimit: Int = 10
    private val mapper = com.dataturntable.json.JsonObjectMappers.create()

    @Transactional
    fun reserve(
        executionId: UUID,
        tenantId: UUID,
        moduleVersionId: UUID,
        environment: ExecutionEnvironment,
        correlationId: String?
    ): Execution {
        require(globalLimit > 0) { "Global execution limit must be positive" }
        require(defaultTenantLimit > 0) { "Per-tenant execution limit must be positive" }
        lockGlobalCoordinator()

        val activeStatuses = setOf(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.CANCELLING)
        val globalActive = activeCount(activeStatuses)
        if (globalActive >= globalLimit) throw ExecutionQuotaExceededException("global", globalLimit, globalActive)

        val tenant = em.find(Tenant::class.java, tenantId)
            ?: throw IllegalArgumentException("Tenant not found")
        val tenantLimit = configuredTenantLimit(tenant)
        val tenantActive = activeCount(activeStatuses, tenantId)
        if (tenantActive >= tenantLimit) throw ExecutionQuotaExceededException("tenant", tenantLimit, tenantActive)

        return Execution().apply {
            id = executionId
            this.tenantId = tenantId
            this.moduleVersionId = moduleVersionId
            status = ExecutionStatus.QUEUED
            backend = environment.backend
            runtimeImage = environment.runtimeImage
            runtimeImageDigest = environment.runtimeImageDigest
            this.correlationId = correlationId
            persist()
        }
    }

    @Transactional
    fun transition(executionId: UUID, target: ExecutionStatus): Boolean {
        val execution = em.find(Execution::class.java, executionId) ?: return false
        if (!ExecutionStateMachine.canTransition(execution.status, target)) return false
        execution.status = target
        execution.updatedAt = Instant.now()
        return true
    }

    private fun activeCount(statuses: Set<ExecutionStatus>, tenantId: UUID? = null): Long {
        val query = em.createQuery(
            "SELECT COUNT(e) FROM Execution e WHERE e.status IN :statuses" +
                if (tenantId == null) "" else " AND e.tenantId = :tenantId",
            java.lang.Long::class.java
        ).setParameter("statuses", statuses)
        if (tenantId != null) query.setParameter("tenantId", tenantId)
        return query.singleResult.toLong()
    }

    private fun lockGlobalCoordinator() {
        var lock = em.find(ExecutionQuotaLock::class.java, ExecutionQuotaLock.GLOBAL_SCOPE, LockModeType.PESSIMISTIC_WRITE)
        if (lock == null) {
            lock = ExecutionQuotaLock().apply { scope = ExecutionQuotaLock.GLOBAL_SCOPE }
            em.persist(lock)
            em.flush()
            em.lock(lock, LockModeType.PESSIMISTIC_WRITE)
        }
        lock.updatedAt = Instant.now()
    }

    private fun configuredTenantLimit(tenant: Tenant): Int {
        val configured = runCatching {
            mapper.readValue(tenant.quota ?: "{}", Map::class.java)["maxConcurrentExecutions"] as? Number
        }.getOrNull()?.toInt()
        return (configured ?: defaultTenantLimit).also {
            require(it > 0) { "Tenant execution limit must be positive" }
        }
    }
}

class ExecutionQuotaExceededException(
    val scope: String,
    val limit: Int,
    val active: Long
) : RuntimeException("$scope execution quota exceeded ($active active; limit $limit)")
