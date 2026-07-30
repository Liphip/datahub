package com.dataturntable.engine

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Central registry for all DataTurntable pipeline Prometheus metrics.
 *
 * Pre-registers timers with [publishPercentileHistogram] so Prometheus emits
 * `_bucket` metrics and Grafana can compute `histogram_quantile()`.
 *
 * Call [pipelineTimer] / [moduleTimer] with the relevant label values —
 * they return (and cache internally via Micrometer) the right Timer instance.
 */
@ApplicationScoped
class PipelineMetrics {

    @Inject
    lateinit var registry: MeterRegistry

    // ── Pipeline-level timers ────────────────────────────────────────────────

    /**
     * Timer for full pipeline execution duration, keyed by pipeline name and status.
     * Histogram buckets: 100 ms → 5 min.
     */
    fun pipelineTimer(pipeline: String, status: String): Timer =
        Timer.builder("dt_pipeline_duration_seconds")
            .description("End-to-end pipeline execution duration")
            .tag("pipeline", pipeline)
            .tag("status", status)
            .publishPercentileHistogram(true)
            .minimumExpectedValue(Duration.ofMillis(100))
            .maximumExpectedValue(Duration.ofMinutes(5))
            .register(registry)

    fun pipelineCounter(status: String, pipeline: String): Counter =
        Counter.builder("dt_pipeline_runs_total")
            .description("Total pipeline run completions")
            .tag("status", status)
            .tag("pipeline", pipeline)
            .register(registry)

    // ── Module-level timers ──────────────────────────────────────────────────

    /**
     * Timer for individual module execution duration, keyed by stage and status.
     * Histogram buckets: 10 ms → 2 min.
     */
    fun moduleTimer(stage: String, status: String): Timer =
        Timer.builder("dt_module_duration_seconds")
            .description("Module container execution duration")
            .tag("stage", stage)
            .tag("status", status)
            .publishPercentileHistogram(true)
            .minimumExpectedValue(Duration.ofMillis(10))
            .maximumExpectedValue(Duration.ofMinutes(2))
            .register(registry)

    fun moduleCounter(stage: String, status: String): Counter =
        Counter.builder("dt_module_executions_total")
            .description("Total module execution completions")
            .tag("stage", stage)
            .tag("status", status)
            .register(registry)

    // ── Container pool counters ──────────────────────────────────────────────

    fun poolAcquireCounter(result: String): Counter =
        Counter.builder("dt_warm_pool_acquire_total")
            .description("Warm container pool acquire outcomes")
            .tag("result", result)
            .register(registry)
}
