package com.dataturntable.connectors

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.TimeUnit

@ApplicationScoped
class ConnectorMetrics {

    @Inject
    lateinit var registry: MeterRegistry

    fun operationCounter(
        connectorType: String?,
        direction: String?,
        operation: String,
        outcome: String,
    ): Counter = Counter.builder("dt_connector_operations_total")
        .description("Connector operation outcomes")
        .tag("connector_type", normalize(connectorType))
        .tag("direction", normalize(direction))
        .tag("operation", normalize(operation))
        .tag("outcome", normalize(outcome))
        .register(registry)

    fun operationTimer(
        connectorType: String?,
        direction: String?,
        operation: String,
        outcome: String,
    ): Timer = Timer.builder("dt_connector_operation_duration_seconds")
        .description("Connector operation duration")
        .tag("connector_type", normalize(connectorType))
        .tag("direction", normalize(direction))
        .tag("operation", normalize(operation))
        .tag("outcome", normalize(outcome))
        .publishPercentileHistogram(true)
        .register(registry)

    fun messageCounter(protocol: String, direction: String, outcome: String): Counter =
        Counter.builder("dt_connector_messages_total")
            .description("Connector messages processed")
            .tag("protocol", normalize(protocol))
            .tag("direction", normalize(direction))
            .tag("outcome", normalize(outcome))
            .register(registry)

    fun connectionCounter(protocol: String, outcome: String): Counter =
        Counter.builder("dt_connector_connections_total")
            .description("Connector connection lifecycle events")
            .tag("protocol", normalize(protocol))
            .tag("outcome", normalize(outcome))
            .register(registry)

    fun pollCounter(outcome: String): Counter =
        Counter.builder("dt_connector_polls_total")
            .description("Scheduled connector poll outcomes")
            .tag("outcome", normalize(outcome))
            .register(registry)

    fun recordOperation(
        connectorType: String?,
        direction: String?,
        operation: String,
        outcome: String,
        startedAtNanos: Long,
    ) {
        val durationNanos = System.nanoTime() - startedAtNanos
        operationCounter(connectorType, direction, operation, outcome).increment()
        operationTimer(connectorType, direction, operation, outcome)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "unknown"
}
