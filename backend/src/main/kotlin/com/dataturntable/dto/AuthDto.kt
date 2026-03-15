package com.dataturntable.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

class LoginRequest @JsonCreator constructor(
    @JsonProperty("email") @Email @NotBlank val email: String,
    @JsonProperty("passwordHash") @NotBlank val passwordHash: String,
    @JsonProperty("tenantId") val tenantId: String? = null
)

data class SetupRequest(
    @NotBlank
    val email: String = "admin@example.com"
)

data class SetupResponse(
    val email: String,
    val password: String,
    val user: UserDto
)

data class AuthResponse(
    val token: String,
    val user: UserDto
)

data class UserDto(
    val id: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val tenantId: String?,
    val roleName: String?,
    val abilities: List<String>,
    val isSuperadmin: Boolean
)
