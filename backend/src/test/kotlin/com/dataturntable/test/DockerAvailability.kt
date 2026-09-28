package com.dataturntable.test

import java.util.concurrent.TimeUnit

/** For `@EnabledIf`: tests that start real containers need a reachable Docker daemon. */
object DockerAvailability {
    @JvmStatic
    fun available(): Boolean = runCatching {
        val process = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)
}
