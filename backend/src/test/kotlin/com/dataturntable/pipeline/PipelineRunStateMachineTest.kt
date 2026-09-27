package com.dataturntable.pipeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineRunStateMachineTest {
    @Test fun `terminal runs cannot resume and cancellation has a deterministic path`() {
        assertTrue(PipelineRunStateMachine.canTransition(PipelineRunStatus.PENDING, PipelineRunStatus.QUEUED))
        assertTrue(PipelineRunStateMachine.canTransition(PipelineRunStatus.CANCELLING, PipelineRunStatus.CANCELLED))
        assertFalse(PipelineRunStateMachine.canTransition(PipelineRunStatus.SUCCESS, PipelineRunStatus.RUNNING))
        assertFalse(PipelineRunStateMachine.canTransition(PipelineRunStatus.TIMED_OUT, PipelineRunStatus.RUNNING))
    }
}
