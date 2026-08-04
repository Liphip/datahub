package com.dataturntable.dto

import java.time.Instant

/**
 * Defines the class `TenantDto`, the contract implemented by this source unit.
 */
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

/**
 * Defines the class `CreateTenantRequest`, the contract implemented by this source unit.
 */
data class CreateTenantRequest(
    val name: String,
    val parentId: String? = null,
    val settings: Map<String, Any> = emptyMap(),
    val quota: Map<String, Any> = emptyMap()
)

/**
 * Defines the class `UpdateTenantRequest`, the contract implemented by this source unit.
 */
data class UpdateTenantRequest(
    val name: String? = null,
    val settings: Map<String, Any>? = null,
    val quota: Map<String, Any>? = null
)
