package com.dataturntable.monitoring

import com.dataturntable.config.RuntimeRoleConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@ApplicationScoped
class MonitoringMetricsUpdater {

    private val log = Logger.getLogger(MonitoringMetricsUpdater::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dt-monitoring-metrics").also { it.isDaemon = true }
    }

    private lateinit var pipelineStatesGauge: MultiGauge
    private lateinit var recentPipelineStatesGauge: MultiGauge
    private lateinit var connectorHealthGauge: MultiGauge
    private lateinit var connectorProtocolGauge: MultiGauge
    private lateinit var connectorMessageFreshnessGauge: MultiGauge
    private lateinit var connectorPollingGauge: MultiGauge

    private val staleRunning = AtomicLong(0)
    private val lastRefreshEpochSeconds = AtomicLong(0)
    private val lastRefreshSuccess = AtomicLong(0)

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var snapshotService: MonitoringSnapshotService

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    fun onStart(@Observes event: StartupEvent) {
        if (!runtimeRoleConfig.isPublicApi()) {
            log.infof("MonitoringMetricsUpdater startup skipped for runtime role %s", runtimeRoleConfig.role)
            return
        }
        pipelineStatesGauge = MultiGauge.builder("dt_pipeline_runs_state")
            .description("Current pipeline runs grouped by state")
            .register(registry)
        recentPipelineStatesGauge = MultiGauge.builder("dt_pipeline_runs_recent_total")
            .description("Pipeline runs over the last 24 hours grouped by state")
            .register(registry)
        connectorHealthGauge = MultiGauge.builder("dt_connector_health_total")
            .description("Connector health grouped by type, direction, and state")
            .register(registry)
        connectorProtocolGauge = MultiGauge.builder("dt_connector_protocol_connections")
            .description("Protocol connector connection states")
            .register(registry)
        connectorMessageFreshnessGauge = MultiGauge.builder("dt_connector_message_freshness_total")
            .description("Freshness of inbound connector traffic")
            .register(registry)
        connectorPollingGauge = MultiGauge.builder("dt_connector_polling_state_total")
            .description("Connector polling scheduling state")
            .register(registry)

        registry.gauge("dt_pipeline_runs_stale_total", staleRunning)
        registry.gauge("dt_monitoring_snapshot_last_refresh_timestamp_seconds", lastRefreshEpochSeconds)
        registry.gauge("dt_monitoring_snapshot_success", lastRefreshSuccess)

        refresh()
        scheduler.scheduleAtFixedRate(::refresh, 30, 30, TimeUnit.SECONDS)
    }

    fun onStop(@Observes event: ShutdownEvent) {
        scheduler.shutdownNow()
    }

    private fun refresh() {
        try {
            val snapshot = snapshotService.collect()
            update(snapshot)
            lastRefreshSuccess.set(1)
            lastRefreshEpochSeconds.set(snapshot.collectedAt.epochSecond)
        } catch (e: Exception) {
            lastRefreshSuccess.set(0)
            log.errorf(
                e,
                "event=monitoring_snapshot component=metrics outcome=failure collected_at=%s error=%s",
                Instant.now(),
                e.message ?: "unknown"
            )
        }
    }

    private fun update(snapshot: MonitoringSnapshot) {
        pipelineStatesGauge.register(
            snapshot.pipelineStates.map { (status, count) ->
                MultiGauge.Row.of(Tags.of("status", status), count.toDouble())
            },
            true
        )
        recentPipelineStatesGauge.register(
            snapshot.recentPipelineStates.map { (status, count) ->
                MultiGauge.Row.of(Tags.of("status", status, "window", "24h"), count.toDouble())
            },
            true
        )
        connectorHealthGauge.register(
            snapshot.connectorHealth.map { (key, count) ->
                MultiGauge.Row.of(tagsFromCompositeKey(key), count.toDouble())
            },
            true
        )
        connectorProtocolGauge.register(
            snapshot.connectorProtocols.map { (key, count) ->
                MultiGauge.Row.of(tagsFromCompositeKey(key), count.toDouble())
            },
            true
        )
        connectorMessageFreshnessGauge.register(
            snapshot.connectorMessageFreshness.map { (key, count) ->
                MultiGauge.Row.of(tagsFromCompositeKey(key), count.toDouble())
            },
            true
        )
        connectorPollingGauge.register(
            snapshot.connectorPolling.map { (key, count) ->
                MultiGauge.Row.of(tagsFromCompositeKey(key), count.toDouble())
            },
            true
        )
        staleRunning.set(snapshot.staleRunningCount)
    }

    private fun tagsFromCompositeKey(key: String): Tags {
        val pairs = key.split(",")
            .mapNotNull { segment ->
                val parts = segment.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
        return Tags.of(*pairs.flatMap { listOf(it.first, it.second) }.toTypedArray())
    }
}
