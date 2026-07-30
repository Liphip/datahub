package com.dataturntable.dto

import com.dataturntable.module.ModuleType
import java.time.Instant

data class CodeModuleDto(
    val id: String?,
    val tenantId: String?,
    val name: String,
    val description: String?,
    val moduleType: ModuleType,
    val isActive: Boolean = true,
    val createdAt: Instant? = null,
    val latestVersionId: String? = null,
    val defaultVersionId: String? = null,
    val versions: List<ModuleVersionDto> = emptyList()
)

data class CreateModuleRequest(
    val name: String,
    val description: String? = null,
    val moduleType: ModuleType
)

data class ModuleVersionDto(
    val id: String?,
    val moduleId: String?,
    val version: Int,
    val versionTag: String?,
    val contentFingerprint: String? = null,
    val code: String,
    val requirements: String = "",
    val config: Map<String, Any> = emptyMap(),
    val isActive: Boolean = true,
    val createdAt: Instant? = null
)

data class CreateModuleVersionRequest(
    val code: String,
    val requirements: String = "",
    val config: Map<String, Any> = emptyMap(),
    val versionTag: String? = null
)

data class TagVersionRequest(
    val versionTag: String
)
