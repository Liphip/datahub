package com.dataturntable.config

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
/**
 * Defines the class `RuntimeRoleConfig`, the contract implemented by this source unit.
 */
class RuntimeRoleConfig(
    @ConfigProperty(name = "dataturntable.runtime.role", defaultValue = "all")
    private val configuredRole: String,
    @ConfigProperty(name = "dataturntable.runtime.background-enabled", defaultValue = "true")
    private val backgroundEnabled: Boolean,
) {
    val role: RuntimeRole = RuntimeRole.fromConfig(configuredRole)

    /**
     * Evaluates the named capability or state predicate.
     */
    fun isControlApi(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.CONTROL_API
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isPublicApi(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.PUBLIC_API
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isIngestEdge(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.INGEST_EDGE
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isPipelineExecutor(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.PIPELINE_EXECUTOR

    /**
     * Implements `backgroundEnabled` for this source unit.
     */
    fun backgroundEnabled(): Boolean = backgroundEnabled
    /**
     * Evaluates the named capability or state predicate.
     */
    fun ownsIngestRuntime(): Boolean = backgroundEnabled && isIngestEdge()
    /**
     * Evaluates the named capability or state predicate.
     */
    fun ownsPipelineRuntime(): Boolean = backgroundEnabled && isPipelineExecutor()
    /**
     * Validates or normalizes input before the caller continues.
     */
    fun requiresKafka(): Boolean = ownsIngestRuntime() || ownsPipelineRuntime()
    /**
     * Implements `exposesPublicMonitoring` for this source unit.
     */
    fun exposesPublicMonitoring(): Boolean = isPublicApi()
    /**
     * Evaluates the named capability or state predicate.
     */
    fun ownsWarmPool(): Boolean = ownsPipelineRuntime()
}
