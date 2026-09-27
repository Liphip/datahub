package com.dataturntable.execution

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.util.UUID

@ApplicationScoped
class ExecutionService {
    @PersistenceContext lateinit var em: EntityManager
    @Inject lateinit var dockerBackend: DockerExecutionBackend
    @Inject lateinit var kubernetesBackend: KubernetesJobExecutionBackend
    @Inject lateinit var capacity: ExecutionCapacityService

    fun submit(tenantId: UUID, moduleVersionId: UUID, environment: ExecutionEnvironment, correlationId: String?): Execution {
        return capacity.reserve(UUID.randomUUID(), tenantId, moduleVersionId, environment, correlationId)
    }

    /**
     * Runs an execution through the selected backend.  Callers intentionally
     * know nothing about Docker or Kubernetes implementation details.
     */
    fun execute(request: ExecutionRequest): ExecutionResult {
        val backend = backendFor(request.environment)
            ?: return ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = "Unsupported execution backend: ${request.environment.backend}")
        if (backend.name != kubernetesBackend.name) {
            capacity.reserve(request.executionId, request.tenantId, request.moduleVersionId, request.environment, request.correlationId)
            capacity.transition(request.executionId, ExecutionStatus.RUNNING)
        } else {
            capacity.reserve(request.executionId, request.tenantId, request.moduleVersionId, request.environment, request.correlationId)
        }
        val result = try {
            backend.submit(request)
        } catch (e: Exception) {
            ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = e.message ?: "Execution backend failed")
        }
        capacity.transition(request.executionId, result.status)
        return result
    }

    fun find(id: UUID): Execution? = em.find(Execution::class.java, id)

    @Transactional
    fun cancel(execution: Execution): Boolean {
        val backend = backendFor(ExecutionEnvironment(execution.runtimeImage, execution.runtimeImageDigest, execution.backend))
        return cancelWithBackend(execution, backend)
    }

    /** Internal seam keeps cancellation propagation independently testable. */
    @Transactional
    internal fun cancelWithBackend(execution: Execution, backend: ExecutionBackend?): Boolean {
        val target = if (execution.status == ExecutionStatus.PENDING || execution.status == ExecutionStatus.QUEUED) ExecutionStatus.CANCELLED else ExecutionStatus.CANCELLING
        if (!ExecutionStateMachine.canTransition(execution.status, target)) return false
        if (target == ExecutionStatus.CANCELLING) {
            backend?.cancel(execution.id ?: return false)
        }
        execution.status = target
        execution.updatedAt = java.time.Instant.now()
        return true
    }

    @Transactional
    fun transition(execution: Execution, target: ExecutionStatus): Boolean {
        if (!ExecutionStateMachine.canTransition(execution.status, target)) return false
        execution.status = target
        execution.updatedAt = java.time.Instant.now()
        return true
    }

    private fun backendFor(environment: ExecutionEnvironment): ExecutionBackend? {
        if (!::dockerBackend.isInitialized || !::kubernetesBackend.isInitialized) return null
        return when (environment.backend) {
        dockerBackend.name -> dockerBackend
        kubernetesBackend.name -> kubernetesBackend
        else -> null
        }
    }
}
