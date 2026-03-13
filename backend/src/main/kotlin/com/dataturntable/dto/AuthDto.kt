package com.dataturntable.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @Email
    @NotBlank
    val email: String,
    @NotBlank
    val password: String,
    val tenantId: String? = null
)

data class RegisterRequest(
    @Email
    @NotBlank
    val email: String,
    @NotBlank
    val password: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val tenantId: String? = null,
    val tenantName: String? = null
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
