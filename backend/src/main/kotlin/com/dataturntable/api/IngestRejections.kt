package com.dataturntable.api

import io.micrometer.core.instrument.Metrics

/**
 * IMP-14: every record or connection an inbound endpoint refuses to protect the platform is
 * counted in `dataturntable_ingest_rejected_total{reason}`. Uses Micrometer's global registry
 * (which Quarkus binds its registry to) so plain-instantiated resources in unit tests count too.
 */
object IngestRejections {
    fun count(reason: String) {
        Metrics.counter("dataturntable.ingest.rejected", "reason", reason).increment()
    }

    fun total(reason: String): Double = Metrics.counter("dataturntable.ingest.rejected", "reason", reason).count()
}
