package com.dataturntable.engine

import com.dataturntable.execution.ExecutionLimits
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Exercises DockerService.executeModule against a real Docker daemon with hostile module code —
 * the "user Python is treated as hostile by default" invariant (NFR-002) previously had zero
 * live-execution test coverage; DockerSandboxPolicyTest only asserts the constructed CLI flags
 * without ever running a container, and DockerServiceTest was @Disabled with an empty test body.
 * Docker Desktop is available in this environment (confirmed via `docker version`) and GitHub
 * Actions' ubuntu-latest runners have Docker preinstalled, matching the precedent already set by
 * PostgresMigrationBehaviorTest (also Docker/Testcontainers-backed and part of the default suite).
 *
 * Every test passes explicit `limits` to executeModule so it always takes the ephemeral-container
 * path (bypassing WarmContainerPool), matching the documented rule that custom-limit requests must
 * not reuse another job's sandboxed container.
 */
@org.junit.jupiter.api.condition.EnabledIf("com.dataturntable.test.DockerAvailability#available")
@QuarkusTest
class DockerServiceHostileModuleTest {

    @Inject
    lateinit var dockerService: DockerService

    private fun moduleVersion(code: String): ModuleVersion = ModuleVersion().apply {
        this.code = code
        this.requirements = ""
    }

    private fun runProcessing(code: String, limits: ExecutionLimits): ModuleExecutionOutput =
        dockerService.executeModule(
            moduleVersion = moduleVersion(code),
            inputData = mapOf("value" to 1),
            pipelineId = "hostile-test",
            runId = UUID.randomUUID().toString(),
            moduleType = ModuleType.PROCESSING,
            limits = limits
        )

    @Test
    fun `a well-behaved module executes successfully and returns its result`() {
        val output = runProcessing(
            code = "def process(input_data, query): return {'doubled': input_data['value'] * 2}",
            limits = ExecutionLimits(timeoutSeconds = 30)
        )
        @Suppress("UNCHECKED_CAST")
        val result = output.result as Map<String, Any?>
        assertEquals(2, (result["doubled"] as Number).toInt())
    }

    @Test
    fun `module code and JSON input containing double quotes reach the container intact`() {
        // Regression test for a real Windows-only bug found while writing this suite:
        // java.lang.ProcessBuilder silently strips unescaped double quotes from `docker run -e`
        // argument values on Windows (CreateProcess re-quoting), so any module source containing
        // a string literal, or any JSON payload, previously arrived corrupted/unparsable inside
        // the container. Fixed via DockerService.envArg. This asserts on the actual mechanism
        // rather than just a happy-path value, so a regression here fails loudly instead of
        // silently returning wrong/empty data the way the original bug did.
        val output = runProcessing(
            code = """
                |def process(input_data, query):
                |    label = "hello \"world\""
                |    return {'label': label, 'echoedValue': input_data['value']}
            """.trimMargin(),
            limits = ExecutionLimits(timeoutSeconds = 30)
        )
        @Suppress("UNCHECKED_CAST")
        val result = output.result as Map<String, Any?>
        assertEquals("hello \"world\"", result["label"])
        assertEquals(1, (result["echoedValue"] as Number).toInt())
    }

    @Test
    fun `an infinite loop is terminated once the configured timeout elapses`() {
        val started = System.currentTimeMillis()
        val error = org.junit.jupiter.api.Assertions.assertThrows(ModuleExecutionException::class.java) {
            runProcessing(
                code = "def process(input_data, query):\n    while True:\n        pass",
                limits = ExecutionLimits(timeoutSeconds = 5)
            )
        }
        val elapsedSeconds = (System.currentTimeMillis() - started) / 1000
        assertTrue(error.message?.contains("timed out") == true, "expected a timeout error, got: ${error.message}")
        // Bounded well above the 5s limit to absorb container startup/teardown overhead without
        // asserting an exact duration; the meaningful assertion is that it terminated at all
        // rather than hanging for the module's true infinite runtime.
        assertTrue(elapsedSeconds < 60, "expected termination near the configured timeout, took ${elapsedSeconds}s")
    }

    @Test
    fun `the container filesystem is read-only outside the tmpfs mount`() {
        val error = org.junit.jupiter.api.Assertions.assertThrows(ModuleExecutionException::class.java) {
            runProcessing(
                code = """
                    |def process(input_data, query):
                    |    with open('/etc/hostile-write-attempt', 'w') as f:
                    |        f.write('escaped')
                    |    return {}
                """.trimMargin(),
                limits = ExecutionLimits(timeoutSeconds = 30)
            )
        }
        // A write attempt outside the writable tmpfs mounts must fail with a non-zero exit
        // (read-only filesystem), not succeed.
        assertTrue(
            error.message?.contains("exit") == true || error.stderr.contains("Read-only", ignoreCase = true),
            "expected a read-only-filesystem failure, got message=${error.message} stderr=${error.stderr}"
        )
    }

    @Test
    fun `network egress is blocked by default even when the module attempts an outbound request`() {
        val error = org.junit.jupiter.api.Assertions.assertThrows(ModuleExecutionException::class.java) {
            runProcessing(
                code = """
                    |def process(input_data, query):
                    |    import socket
                    |    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    |    s.settimeout(5)
                    |    s.connect(('8.8.8.8', 53))
                    |    return {}
                """.trimMargin(),
                // allowNetwork on the request is irrelevant here: DockerService.networkMode also
                // requires the deployment-level `dataturntable.docker.allow-network` flag, which
                // defaults to false and is not set in the test profile.
                limits = ExecutionLimits(timeoutSeconds = 20, allowNetwork = true)
            )
        }
        assertFalse(error.message?.contains("doubled") == true)
    }

    @Test
    fun `oversized module output is truncated to the configured cap rather than growing unbounded`() {
        val output = runProcessing(
            code = """
                |def process(input_data, query):
                |    print('x' * 5000)
                |    return {'ok': True}
            """.trimMargin(),
            limits = ExecutionLimits(timeoutSeconds = 30, maxOutputBytes = 1024)
        )
        assertTrue(output.stdout.length <= 1024, "stdout should be capped at 1024 bytes, was ${output.stdout.length}")
    }

    @Test
    fun `a fork bomb is bounded by the configured pids limit rather than exhausting host resources`() {
        val error = org.junit.jupiter.api.Assertions.assertThrows(ModuleExecutionException::class.java) {
            runProcessing(
                code = """
                    |def process(input_data, query):
                    |    import os
                    |    for _ in range(10000):
                    |        os.fork()
                    |    return {}
                """.trimMargin(),
                limits = ExecutionLimits(timeoutSeconds = 15, maxPids = 16)
            )
        }
        // Blocked by --pids-limit (fork raises BlockingIOError/OSError once the cap is hit) rather
        // than succeeding or hanging until the 15s timeout.
        assertFalse(error.message?.contains("timed out") == true, "fork bomb should be rejected by pids-limit, not time out: ${error.message}")
    }
}
