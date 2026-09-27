package com.dataturntable.security

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logmanager.ExtLogRecord
import java.util.logging.Filter
import java.util.logging.LogManager
import java.util.logging.LogRecord

/**
 * A last line of defense against secret leakage into logs, on top of (not instead of) the
 * call-site [SensitiveDataRedactor] usage already wired into 13 files as of 2026-08-20. Call-site
 * redaction requires every developer to remember to redact at every logging call; this filter
 * catches whatever they forget by rewriting every log record's formatted message before any
 * handler (console, file, etc.) writes it out.
 *
 * A `java.util.logging.Filter` set on a `Logger` (via `Logger.setFilter`) only runs for records
 * logged directly against *that* logger instance — it does not run for records from child loggers
 * merely propagating up through it, which is how essentially all application logging actually
 * reaches the root logger's handlers. The correct global interception point is therefore this
 * filter installed on every root-logger `Handler` (the console handler in Quarkus's default setup),
 * since handlers — unlike logger-level filters — do receive every propagated record regardless of
 * which logger originated it.
 *
 * Uses JBoss LogManager's [ExtLogRecord] (Quarkus's actual runtime logging implementation, not
 * plain `java.util.logging`), whose `setMessage` allows rewriting the already-formatted message —
 * this is what lets the filter operate on the final rendered text (MDC/parameter substitution
 * already applied), not just the raw message pattern.
 */
class GlobalLogRedactionFilter(private val redactor: SensitiveDataRedactor) : Filter {
    override fun isLoggable(record: LogRecord): Boolean {
        if (record is ExtLogRecord) {
            val formatted = record.formattedMessage
            val redacted = redactor.text(formatted)
            if (redacted != null && redacted != formatted) {
                record.setMessage(redacted, ExtLogRecord.FormatStyle.NO_FORMAT)
            }
        }
        return true
    }
}

@ApplicationScoped
class GlobalLogRedactionFilterInstaller {
    @Inject
    lateinit var redactor: SensitiveDataRedactor

    fun install(@Observes event: StartupEvent) {
        val filter = GlobalLogRedactionFilter(redactor)
        val rootLogger = LogManager.getLogManager().getLogger("")
        rootLogger.handlers.forEach { handler -> handler.filter = filter }
    }
}
