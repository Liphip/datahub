package com.dataturntable.execution

import java.time.Instant
import java.util.UUID
import com.dataturntable.module.ModuleType

enum class ExecutionStatus { PENDING, QUEUED, RUNNING, CANCELLING, CANCELLED, SUCCESS, FAILED, TIMED_OUT }

data class ExecutionLimits(
    val timeoutSeconds: Int = 300,
    val cpuCores: Double = 1.0,
    val memoryMiB: Int = 512,
    val maxPids: Int = 128,
    val temporaryDiskMiB: Int = 256,
    val maxOutputBytes: Int = 1_048_576,
    val allowNetwork: Boolean = false
) {
    init {
        require(timeoutSeconds in 1..3_600) { "timeoutSeconds must be between 1 and 3600" }
        require(cpuCores > 0.0 && cpuCores <= 16.0) { "cpuCores must be between 0 and 16" }
        require(memoryMiB in 64..65_536) { "memoryMiB must be between 64 and 65536" }
        require(maxPids in 1..4_096) { "maxPids must be between 1 and 4096" }
        require(temporaryDiskMiB in 16..65_536) { "temporaryDiskMiB must be between 16 and 65536" }
        require(maxOutputBytes in 1_024..16_777_216) { "maxOutputBytes must be between 1024 and 16777216" }
    }
}

data class ExecutionEnvironment(
    val runtimeImage: String,
    val runtimeImageDigest: String? = null,
    val backend: String
)

data class ExecutionRequest(
    val executionId: UUID,
    val tenantId: UUID,
    val moduleVersionId: UUID,
    val input: Any?,
    val moduleType: ModuleType,
    val connectorMetadata: Map<String, Any?>? = null,
    val environment: ExecutionEnvironment,
    val limits: ExecutionLimits,
    val correlationId: String? = null
)

data class ExecutionResult(
    val executionId: UUID,
    val status: ExecutionStatus,
    val completedAt: Instant = Instant.now(),
    val output: Any? = null,
    val stdout: String = "",
    val stderr: String = "",
    val errorMessage: String? = null,
    val exitCode: Int? = null
)

interface ExecutionBackend {
    val name: String
    fun submit(request: ExecutionRequest): ExecutionResult
    fun cancel(executionId: UUID): Boolean
}
