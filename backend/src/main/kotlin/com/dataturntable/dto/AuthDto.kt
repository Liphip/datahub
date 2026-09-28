package com.dataturntable.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/**
 * Defines the class `LoginRequest`, the contract implemented by this source unit.
 */
class LoginRequest @JsonCreator constructor(
    @JsonProperty("email") @Email @NotBlank val email: String,
    @JsonProperty("password") @NotBlank val password: String,
    @JsonProperty("tenantId") val tenantId: String? = null
)

/**
 * Defines the class `SetupRequest`, the contract implemented by this source unit.
 */
data class SetupRequest(
    @NotBlank
    val tenantName: String = "Root Tenant",
    @NotBlank
    val email: String = "admin@example.com",
    val password: String? = null
)

/**
 * Defines the class `SetupResponse`, the contract implemented by this source unit.
 */
data class SetupResponse(
    val email: String,
    val password: String,
    val user: UserDto
)

/**
 * Defines the class `AuthResponse`, the contract implemented by this source unit.
 */
data class AuthResponse(
    val token: String,
    val user: UserDto
)

/**
 * Defines the class `TenantContextDto`, the contract implemented by this source unit.
 */
data class TenantContextDto(
    val tenantId: String,
    val tenantName: String,
    val roleName: String?
)

/**
 * Defines the class `UserDto`, the contract implemented by this source unit.
 */
data class UserDto(
    val id: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val tenantId: String?,
    val roleName: String?,
    val abilities: List<String>,
    val superadmin: Boolean
)
