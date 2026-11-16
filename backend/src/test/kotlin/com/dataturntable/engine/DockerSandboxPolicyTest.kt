package com.dataturntable.engine

import com.dataturntable.execution.ExecutionLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerSandboxPolicyTest {
    @Test
    fun `ephemeral execution command retains the restricted sandbox policy`() {
        val service = DockerService().apply {
            allowNetwork = false
            networkName = "dataturntable"
        }
        val limits = ExecutionLimits(cpuCores = 0.5, memoryMiB = 256, maxPids = 64, temporaryDiskMiB = 128)

        val args = service.sandboxArguments(limits)

        assertEquals("none", service.networkMode(limits.copy(allowNetwork = true)))
        assertTrue(args.contains("--read-only"))
        assertTrue(args.windowed(2).contains(listOf("--user", "1000:1000")))
        assertTrue(args.windowed(2).contains(listOf("--cap-drop", "ALL")))
        assertTrue(args.windowed(2).contains(listOf("--security-opt", "no-new-privileges:true")))
        assertTrue(args.windowed(2).contains(listOf("--pids-limit", "64")))
        assertTrue(args.windowed(2).contains(listOf("--memory", "256m")))
        assertTrue(args.windowed(2).contains(listOf("--cpus", "0.5")))
        assertTrue(args.windowed(2).contains(listOf("--tmpfs", "/tmp:size=128m")))
        assertFalse(args.any { it.contains("docker.sock", ignoreCase = true) || it == "--privileged" })
    }

    @Test
    fun `execution egress requires both deployment and request opt in`() {
        val service = DockerService().apply { networkName = "isolated-network" }
        val requested = ExecutionLimits(allowNetwork = true)

        service.allowNetwork = false
        assertEquals("none", service.networkMode(requested))

        service.allowNetwork = true
        assertEquals("isolated-network", service.networkMode(requested))
        assertEquals("none", service.networkMode(requested.copy(allowNetwork = false)))
    }

    @Test
    fun `a warm container gets a network only where the deployment allows module egress`() {
        val closed = WarmContainerPool().apply { allowNetwork = false }
        assertFalse(closed.needsNetwork("requests==2.32.0"), "requirements alone must not open the network")
        assertFalse(closed.needsNetwork(""))

        val open = WarmContainerPool().apply { allowNetwork = true }
        assertTrue(open.needsNetwork("requests==2.32.0"))
        assertFalse(open.needsNetwork(""), "a container without requirements never needs a network")
    }

    @Test
    fun `captured output never exceeds its configured hard cap`() {
        val service = DockerService()
        val sink = StringBuilder()

        service.appendBoundedOutput(sink, "12345", 5)
        service.appendBoundedOutput(sink, "should-not-appear", 5)

        assertEquals("12345", sink.toString())
        assertEquals(5, sink.length)
    }

    @Test
    fun `env args without quotes are passed through unchanged on every OS`() {
        assertEquals("MODULE_TYPE=PROCESSING", envArg("MODULE_TYPE", "PROCESSING"))
    }

    @Test
    fun `env args containing double quotes are escaped for Windows ProcessBuilder`() {
        // java.lang.ProcessBuilder's Windows argument re-quoting (CreateProcess) silently strips
        // unescaped embedded double quotes — verified by reproduction: an unescaped
        // `-e INPUT_DATA={"value":1}` arrived at the container as the invalid-JSON
        // `{value:1}`, so a module's json.loads() silently returned an empty payload instead of
        // failing loudly. This test only exercises the Windows branch when actually running on
        // Windows (this dev environment); on Linux/CI, `os.name` doesn't start with "Windows" and
        // envArg is a passthrough — POSIX exec() needs no escaping since argv is passed literally.
        val result = envArg("INPUT_DATA", """{"value":1}""")
        if (System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true) {
            assertEquals("""INPUT_DATA={\"value\":1}""", result)
        } else {
            assertEquals("""INPUT_DATA={"value":1}""", result)
        }
    }
}
