package com.dataturntable.dto

import java.time.Instant

data class TenantDto(
    val id: String?,
    val name: String,
    val parentId: String?,
    val settings: Map<String, Any> = emptyMap(),
    val quota: Map<String, Any> = emptyMap(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val children: List<TenantDto> = emptyList()
)

data class CreateTenantRequest(
    val name: String,
    val parentId: String? = null,
    val settings: Map<String, Any> = emptyMap(),
    val quota: Map<String, Any> = emptyMap()
)

data class UpdateTenantRequest(
    val name: String? = null,
    val settings: Map<String, Any>? = null,
    val quota: Map<String, Any>? = null
)
