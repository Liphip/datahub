package com.dataturntable.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/** MOD-05 / NFR-S3 policy of the dependency install, asserted without a Docker daemon. */
class ModuleDependencyCacheTest {
    private fun cache() = ModuleDependencyCache().apply {
        baseImage = "python:test"
        installNetwork = "bridge"
    }

    @Test
    fun `equivalent requirement texts share one volume and different ones do not`() {
        val cache = cache()
        assertEquals(cache.volumeName("pandas==2.3.3\nnumpy"), cache.volumeName("  pandas==2.3.3 # pinned\n\nnumpy\n"))
        assertNotEquals(cache.volumeName("pandas==2.3.3"), cache.volumeName("pandas==2.3.2"))
        assertTrue(cache.volumeName("x").matches(Regex("dt-pkgs-[0-9a-f]{24}")))
        assertNull(cache.ensure(" \n# only a comment\n"), "nothing to install, no Docker call")
    }

    @Test
    fun `the install container has a network but no module data, runs unprivileged and takes wheels only`() {
        val args = cache().installArgs("dt-pkgs-1", listOf("pandas==2.3.3"))
        assertTrue(args.windowed(2).contains(listOf("--network", "bridge")))
        assertTrue(args.windowed(2).contains(listOf("--user", "1000:1000")))
        assertTrue("--read-only" in args)
        assertTrue(args.windowed(2).contains(listOf("--cap-drop", "ALL")))
        assertTrue("DT_ONLY_BINARY=1" in args, "sdists (whose build scripts run with network) are refused by default")
        assertFalse(args.any { it.startsWith("INPUT_DATA=") || it.startsWith("MODULE_CODE=") || it.startsWith("MODULE_CONFIG=") || it.startsWith("MODEL_ARTIFACTS=") })
        assertFalse(args.any { it.contains("docker.sock") })

        val withSources = cache().apply { allowSourceBuilds = true }.installArgs("dt-pkgs-1", listOf("x"))
        assertTrue("DT_ONLY_BINARY=0" in withSources)
        val mirrored = cache().apply { indexUrl = Optional.of("https://pypi.internal/simple"); proxy = Optional.of("http://proxy:3128") }
            .installArgs("dt-pkgs-1", listOf("x"))
        assertTrue("PIP_INDEX_URL=https://pypi.internal/simple" in mirrored)
        assertTrue("HTTPS_PROXY=http://proxy:3128" in mirrored)
    }

    @Test
    fun `the root step only hands the volume to the module user, without a network`() {
        val args = cache().prepareArgs("dt-pkgs-1")
        assertTrue(args.windowed(2).contains(listOf("--network", "none")))
        assertEquals(listOf("chown", "1000:1000", "/pkgs"), args.takeLast(3))
    }

    @Test
    fun `a module run gets the packages read-only and on PYTHONPATH`() {
        val args = cache().mountArgs("dt-pkgs-1")
        assertEquals(listOf("-v", "dt-pkgs-1:/opt/dt-packages:ro", "-e", "PYTHONPATH=/opt/dt-packages/site"), args)
    }
}
