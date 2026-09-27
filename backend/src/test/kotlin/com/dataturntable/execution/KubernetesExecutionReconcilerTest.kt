package com.dataturntable.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class KubernetesExecutionReconcilerTest {
    @Test
    fun `a completed native Job advances a queued execution through running to success`() {
        val execution = Execution().apply { id = UUID.randomUUID(); status = ExecutionStatus.QUEUED }
        KubernetesExecutionReconciler().reconcileObservation(execution, KubernetesJobObservation.State(ExecutionStatus.SUCCESS))
        assertEquals(ExecutionStatus.SUCCESS, execution.status)
    }

    @Test
    fun `a deleted native Job completes a cancelling execution`() {
        val execution = Execution().apply { id = UUID.randomUUID(); status = ExecutionStatus.CANCELLING }
        KubernetesExecutionReconciler().reconcileObservation(execution, KubernetesJobObservation.NotFound)
        assertEquals(ExecutionStatus.CANCELLED, execution.status)
    }

    @Test
    fun `an unavailable Kubernetes API leaves an execution unchanged`() {
        val execution = Execution().apply { id = UUID.randomUUID(); status = ExecutionStatus.RUNNING }
        KubernetesExecutionReconciler().reconcileObservation(execution, KubernetesJobObservation.Unavailable)
        assertEquals(ExecutionStatus.RUNNING, execution.status)
    }
}
