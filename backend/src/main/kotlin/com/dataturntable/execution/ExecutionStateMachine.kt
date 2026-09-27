package com.dataturntable.execution

object ExecutionStateMachine {
    private val transitions = mapOf(
        ExecutionStatus.PENDING to setOf(ExecutionStatus.QUEUED, ExecutionStatus.CANCELLED),
        ExecutionStatus.QUEUED to setOf(ExecutionStatus.RUNNING, ExecutionStatus.CANCELLING, ExecutionStatus.CANCELLED, ExecutionStatus.FAILED),
        ExecutionStatus.RUNNING to setOf(ExecutionStatus.SUCCESS, ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT, ExecutionStatus.CANCELLING),
        ExecutionStatus.CANCELLING to setOf(ExecutionStatus.CANCELLED, ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT)
    )

    fun canTransition(from: ExecutionStatus, to: ExecutionStatus): Boolean = to in (transitions[from] ?: emptySet())
    fun isTerminal(status: ExecutionStatus): Boolean = status in setOf(ExecutionStatus.CANCELLED, ExecutionStatus.SUCCESS, ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT)
}
