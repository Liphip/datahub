package com.dataturntable.execution

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionStateMachineTest {
    @Test fun `allows documented lifecycle and rejects terminal revival`() {
        assertTrue(ExecutionStateMachine.canTransition(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING))
        assertTrue(ExecutionStateMachine.canTransition(ExecutionStatus.RUNNING, ExecutionStatus.CANCELLING))
        assertTrue(ExecutionStateMachine.canTransition(ExecutionStatus.CANCELLING, ExecutionStatus.CANCELLED))
        assertFalse(ExecutionStateMachine.canTransition(ExecutionStatus.CANCELLED, ExecutionStatus.RUNNING))
        assertFalse(ExecutionStateMachine.canTransition(ExecutionStatus.SUCCESS, ExecutionStatus.FAILED))
    }
}
