package com.dataturntable.dto

import com.dataturntable.pipeline.TriggerType
import java.time.Instant

data class PipelineDto(
    val id: String?,
    val tenantId: String?,
    val name: String,
    val description: String?,
    val importModuleVersionId: String?,
    val processingModuleVersionIds: List<String> = emptyList(),
    val exportModuleVersionIds: List<String> = emptyList(),
    val kafkaConsumerGroup: String?,
    val isActive: Boolean = false,
    val config: Map<String, Any> = emptyMap(),
    val createdAt: Instant? = null,
    val runs: List<PipelineRunDto> = emptyList()
)

data class CreatePipelineRequest(
    val name: String,
    val description: String? = null,
    val importModuleVersionId: String? = null,
    val processingModuleVersionIds: List<String> = emptyList(),
    val exportModuleVersionIds: List<String> = emptyList(),
    val config: Map<String, Any> = emptyMap()
)

data class UpdatePipelineRequest(
    val name: String? = null,
    val description: String? = null,
    val importModuleVersionId: String? = null,
    val processingModuleVersionIds: List<String>? = null,
    val exportModuleVersionIds: List<String>? = null,
    val isActive: Boolean? = null,
    val config: Map<String, Any>? = null
)

data class PipelineRunDto(
    val id: String?,
    val pipelineId: String?,
    val status: String,
    val triggerType: TriggerType,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val errorMessage: String?,
    val metrics: Map<String, Any> = emptyMap()
)

data class TriggerPipelineRequest(
    val triggerType: TriggerType = TriggerType.MANUAL
)
