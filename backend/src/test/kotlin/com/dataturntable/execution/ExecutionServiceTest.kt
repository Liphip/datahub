package com.dataturntable.execution

import com.dataturntable.module.ModuleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ExecutionServiceTest {
    @Test
    fun `cancelling a queued execution does not invoke a backend`() {
        val backend = RecordingBackend()
        val execution = Execution().apply { id = UUID.randomUUID(); status = ExecutionStatus.QUEUED }

        assertTrue(ExecutionService().cancelWithBackend(execution, backend))
        assertEquals(ExecutionStatus.CANCELLED, execution.status)
        assertFalse(backend.cancelCalled)
    }

    @Test
    fun `cancelling a running execution propagates to backend`() {
        val backend = RecordingBackend()
        val execution = Execution().apply { id = UUID.randomUUID(); status = ExecutionStatus.RUNNING }

        assertTrue(ExecutionService().cancelWithBackend(execution, backend))
        assertEquals(ExecutionStatus.CANCELLING, execution.status)
        assertTrue(backend.cancelCalled)
    }

    @Test
    fun `execution limits reject unsafe values before a backend is selected`() {
        assertThrows(IllegalArgumentException::class.java) { ExecutionLimits(timeoutSeconds = 0) }
        assertThrows(IllegalArgumentException::class.java) { ExecutionLimits(memoryMiB = 32) }
        assertThrows(IllegalArgumentException::class.java) { ExecutionLimits(maxPids = 0) }
        assertThrows(IllegalArgumentException::class.java) { ExecutionLimits(maxOutputBytes = 100) }
    }

    private class RecordingBackend : ExecutionBackend {
        override val name = "recording"
        var cancelCalled = false
        override fun submit(request: ExecutionRequest) = ExecutionResult(request.executionId, ExecutionStatus.SUCCESS)
        override fun cancel(executionId: UUID): Boolean {
            cancelCalled = true
            return true
        }
    }
}
