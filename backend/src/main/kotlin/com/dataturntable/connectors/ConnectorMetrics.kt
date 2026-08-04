package com.dataturntable.connectors

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.TimeUnit

@ApplicationScoped
/**
 * Defines the class `ConnectorMetrics`, the contract implemented by this source unit.
 */
class ConnectorMetrics {

    @Inject
    lateinit var registry: MeterRegistry

    /**
     * Implements `operationCounter` for this source unit.
     */
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

    /**
     * Implements `operationTimer` for this source unit.
     */
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

    /**
     * Implements `messageCounter` for this source unit.
     */
    fun messageCounter(protocol: String, direction: String, outcome: String): Counter =
        Counter.builder("dt_connector_messages_total")
            .description("Connector messages processed")
            .tag("protocol", normalize(protocol))
            .tag("direction", normalize(direction))
            .tag("outcome", normalize(outcome))
            .register(registry)

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    fun connectionCounter(protocol: String, outcome: String): Counter =
        Counter.builder("dt_connector_connections_total")
            .description("Connector connection lifecycle events")
            .tag("protocol", normalize(protocol))
            .tag("outcome", normalize(outcome))
            .register(registry)

    /**
     * Implements `pollCounter` for this source unit.
     */
    fun pollCounter(outcome: String): Counter =
        Counter.builder("dt_connector_polls_total")
            .description("Scheduled connector poll outcomes")
            .tag("outcome", normalize(outcome))
            .register(registry)

    /**
     * Implements `recordOperation` for this source unit.
     */
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

    /**
     * Validates or normalizes input before the caller continues.
     */
    private fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "unknown"
}
