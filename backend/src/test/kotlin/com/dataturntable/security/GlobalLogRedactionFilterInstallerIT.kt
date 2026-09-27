package com.dataturntable.security

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.LogManager

/**
 * Verifies `GlobalLogRedactionFilterInstaller` actually installs the filter on a real, fully
 * booted Quarkus application's root logger handlers — not just that the filter class works in
 * isolation (see `GlobalLogRedactionFilterTest` for that).
 */
@QuarkusTest
class GlobalLogRedactionFilterInstallerIT {
    @Test
    fun `every root logger handler has the redaction filter installed after startup`() {
        val rootLogger = LogManager.getLogManager().getLogger("")
        val handlers = rootLogger.handlers

        assertTrue(handlers.isNotEmpty(), "expected at least one handler on the root logger (e.g. the console handler)")
        handlers.forEach { handler ->
            assertTrue(
                handler.filter is GlobalLogRedactionFilter,
                "handler ${handler.javaClass.name} does not have GlobalLogRedactionFilter installed"
            )
        }
    }
}
