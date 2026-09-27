package com.dataturntable.execution

import com.dataturntable.module.ModuleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class KubernetesJobExecutionBackendTest {
    @Test
    fun `job names are deterministic for cancellation`() {
        val executionId = UUID.randomUUID()
        assertEquals("dt-exec-$executionId", KubernetesJobExecutionBackend().jobName(executionId))
    }

    @Test
    fun `native Job observations map terminal and active states`() {
        val backend = KubernetesJobExecutionBackend()
        assertEquals(
            ExecutionStatus.SUCCESS,
            backend.statusFromJob(mapOf("status" to mapOf("conditions" to listOf(mapOf("type" to "Complete", "status" to "True")))))
        )
        assertEquals(
            ExecutionStatus.TIMED_OUT,
            backend.statusFromJob(mapOf("status" to mapOf("conditions" to listOf(mapOf("type" to "Failed", "status" to "True", "reason" to "DeadlineExceeded")))))
        )
        assertEquals(ExecutionStatus.RUNNING, backend.statusFromJob(mapOf("status" to mapOf("active" to 1))))
        assertEquals(ExecutionStatus.QUEUED, backend.statusFromJob(emptyMap()))
    }

    @Test
    fun `native Job manifest enforces the restricted execution baseline`() {
        val request = ExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), mapOf("value" to 1), ModuleType.PROCESSING,
            environment = ExecutionEnvironment("registry.example/module@sha256:abc", backend = "kubernetes"),
            limits = ExecutionLimits(timeoutSeconds = 30, cpuCores = 0.5, memoryMiB = 128))
        val manifest = KubernetesJobExecutionBackend().jobManifest(request, "print('ok')")
        @Suppress("UNCHECKED_CAST") val spec = manifest["spec"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST") val pod = ((spec["template"] as Map<String, Any>)["spec"] as Map<String, Any>)
        @Suppress("UNCHECKED_CAST") val container = (pod["containers"] as List<Map<String, Any>>).single()
        @Suppress("UNCHECKED_CAST") val security = container["securityContext"] as Map<String, Any>
        assertEquals("Never", pod["restartPolicy"])
        assertEquals(false, pod["automountServiceAccountToken"])
        assertEquals(true, (pod["securityContext"] as Map<String, Any>)["runAsNonRoot"])
        assertEquals(false, security["allowPrivilegeEscalation"])
        assertEquals(true, security["readOnlyRootFilesystem"])
        assertEquals(30, spec["activeDeadlineSeconds"])
        assertTrue((security["capabilities"] as Map<String, List<String>>)["drop"]!!.contains("ALL"))
        assertFalse(manifest.toString().contains("docker.sock"))
    }

    @Test
    fun `Kubernetes backend refuses network-enabled user jobs until an explicit network policy exists`() {
        val request = ExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, ModuleType.PROCESSING,
            environment = ExecutionEnvironment("registry.example/module@sha256:abc", backend = "kubernetes"),
            limits = ExecutionLimits(allowNetwork = true))
        val backend = KubernetesJobExecutionBackend()
        val result = backend.submit(request)
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.errorMessage!!.contains("network access"))
    }
}
