package com.dataturntable.config

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class RuntimeRoleConfig(
    @ConfigProperty(name = "dataturntable.runtime.role", defaultValue = "all")
    private val configuredRole: String,
    @ConfigProperty(name = "dataturntable.runtime.background-enabled", defaultValue = "true")
    private val backgroundEnabled: Boolean,
) {
    val role: RuntimeRole = RuntimeRole.fromConfig(configuredRole)

    fun isControlApi(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.CONTROL_API
    fun isPublicApi(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.PUBLIC_API
    fun isIngestEdge(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.INGEST_EDGE
    fun isPipelineExecutor(): Boolean = role == RuntimeRole.ALL || role == RuntimeRole.PIPELINE_EXECUTOR

    fun backgroundEnabled(): Boolean = backgroundEnabled
    fun ownsIngestRuntime(): Boolean = backgroundEnabled && isIngestEdge()
    fun ownsPipelineRuntime(): Boolean = backgroundEnabled && isPipelineExecutor()
    fun requiresKafka(): Boolean = ownsIngestRuntime() || ownsPipelineRuntime()
    fun requiresRedis(): Boolean = ownsIngestRuntime() || ownsPipelineRuntime()
    fun exposesPublicMonitoring(): Boolean = isPublicApi()
    fun ownsWarmPool(): Boolean = ownsPipelineRuntime()
}
