package com.dataturntable.security

import org.jboss.logmanager.ExtLogRecord
import org.jboss.logmanager.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobalLogRedactionFilterTest {
    private val filter = GlobalLogRedactionFilter(SensitiveDataRedactor())

    private fun record(message: String): ExtLogRecord =
        ExtLogRecord(Level.INFO, message, "test.logger")

    @Test
    fun `rewrites a record's formatted message when it contains a credential-shaped value`() {
        val record = record("Failed to dispatch alert: https://hooks.example.com/notify?api_key=sk_live_abc123")

        val allowed = filter.isLoggable(record)

        assertTrue(allowed, "the filter must never suppress a log record, only rewrite it")
        assertFalse(record.formattedMessage.contains("sk_live_abc123"))
        assertTrue(record.formattedMessage.contains("[REDACTED]"))
    }

    @Test
    fun `leaves an already-safe message untouched`() {
        val record = record("Pipeline run 4f2c... completed successfully")
        val original = record.formattedMessage

        filter.isLoggable(record)

        assertEquals(original, record.formattedMessage)
    }

    @Test
    fun `handles a record with a substituted parameter, not just the raw pattern`() {
        // Application code logs via JBoss Logger's printf-style helpers (e.g. LOG.errorf("...%s",
        // value)), which produce records using FormatStyle.PRINTF — MESSAGE_FORMAT ({0}/{1}
        // placeholders) is only this constructor family's *default*, not what real call sites use.
        val record = ExtLogRecord(Level.ERROR, "Grafana sync failed for state %s: %s", ExtLogRecord.FormatStyle.PRINTF, "test.logger")
        record.parameters = arrayOf("state-1", "password=hunter2")

        filter.isLoggable(record)

        // getFormattedMessage() applies parameter substitution before the filter runs, so the
        // filter must catch secrets that only appear after substitution, not just in the pattern.
        assertFalse(record.formattedMessage.contains("hunter2"))
        assertTrue(record.formattedMessage.contains("[REDACTED]"))
    }

    @Test
    fun `never returns false, since suppressing a record is not this filter's job`() {
        val record = record("anything at all")
        assertTrue(filter.isLoggable(record))
    }
}
