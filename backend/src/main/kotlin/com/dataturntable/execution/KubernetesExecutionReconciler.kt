package com.dataturntable.execution

import com.dataturntable.config.RuntimeRoleConfig
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reconciles durable execution records after submitter loss or Kubernetes Job completion. */
@ApplicationScoped
class KubernetesExecutionReconciler {
    private val log = Logger.getLogger(KubernetesExecutionReconciler::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dt-kubernetes-execution-reconciler").also { it.isDaemon = true }
    }

    @PersistenceContext lateinit var em: EntityManager
    @Inject lateinit var backend: KubernetesJobExecutionBackend
    @Inject lateinit var runtimeRoleConfig: RuntimeRoleConfig
    @ConfigProperty(name = "dataturntable.execution.kubernetes.reconcile-interval-seconds", defaultValue = "10")
    var reconcileIntervalSeconds: Long = 10

    fun onStart(@Observes event: StartupEvent) {
        if (!runtimeRoleConfig.ownsPipelineRuntime()) return
        executor.scheduleWithFixedDelay(::reconcileSafely, 0, reconcileIntervalSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
    }

    fun onStop(@Observes event: ShutdownEvent) = executor.shutdownNow()

    private fun reconcileSafely() = try {
        reconcileActive()
    } catch (error: Exception) {
        log.warn("Kubernetes execution reconciliation failed; active records will be retried", error)
    }

    @Transactional
    fun reconcileActive(): Int {
        val executions = em.createQuery(
            "SELECT e FROM Execution e WHERE e.backend = :backend AND e.status IN :statuses",
            Execution::class.java
        )
            .setParameter("backend", backend.name)
            .setParameter("statuses", setOf(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.CANCELLING))
            .setMaxResults(200)
            .resultList
        executions.forEach(::reconcile)
        return executions.size
    }

    private fun reconcile(execution: Execution) {
        val id = execution.id ?: return
        reconcileObservation(execution, backend.observe(id))
    }

    internal fun reconcileObservation(execution: Execution, observation: KubernetesJobObservation) {
        when (observation) {
            is KubernetesJobObservation.State -> advance(execution, observation.status)
            KubernetesJobObservation.NotFound -> if (execution.status == ExecutionStatus.CANCELLING) advance(execution, ExecutionStatus.CANCELLED)
            KubernetesJobObservation.Unavailable -> Unit
        }
    }

    private fun advance(execution: Execution, target: ExecutionStatus) {
        if (execution.status == target) return
        if (target in setOf(ExecutionStatus.SUCCESS, ExecutionStatus.TIMED_OUT) && execution.status == ExecutionStatus.QUEUED) {
            execution.status = ExecutionStatus.RUNNING
        }
        if (!ExecutionStateMachine.canTransition(execution.status, target)) return
        execution.status = target
        execution.updatedAt = Instant.now()
    }
}
