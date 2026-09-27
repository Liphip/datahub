package com.dataturntable.dto

/**
 * Defines the enum class `Ability`, the contract implemented by this source unit.
 */
enum class Ability(val value: String) {
    // Modules
    MODULE_READ("module:read"),
    MODULE_WRITE("module:write"),
    MODULE_DELETE("module:delete"),
    
    // Pipelines
    PIPELINE_READ("pipeline:read"),
    PIPELINE_WRITE("pipeline:write"),
    PIPELINE_EXECUTE("pipeline:execute"),
    
    // Data
    DATA_READ("data:read"),
    DATA_WRITE("data:write"),
    DATA_EXPORT("data:export"),
    
    // Alerts
    ALERT_READ("alert:read"),
    ALERT_CONFIGURE("alert:configure"),
    
    // Metadata (devices, meters, locations, ... and their relations)
    METADATA_READ("metadata:read"),
    METADATA_WRITE("metadata:write"),

    // Connectors
    CONNECTOR_READ("connector:read"),
    CONNECTOR_WRITE("connector:write"),
    CONNECTOR_DELETE("connector:delete"),

    // Secrets (credentials referenced by connectors and alert channels)
    SECRET_MANAGE("secret:manage"),

    // Users
    USER_READ("user:read"),
    USER_WRITE("user:write"),
    USER_DELETE("user:delete"),

    // Tenant
    TENANT_MANAGE("tenant:manage");
    
    companion object {
        /**
         * Implements `fromValue` for this source unit.
         */
        fun fromValue(value: String): Ability? = entries.find { it.value == value }
        
        /**
         * Implements `all` for this source unit.
         */
        fun all(): List<String> = entries.map { it.value }
    }
}

/**
 * Defines the class `RoleDto`, the contract implemented by this source unit.
 */
data class RoleDto(
    val id: String?,
    val tenantId: String?,
    val name: String,
    val description: String?,
    val abilities: List<String>,
    val isDefault: Boolean,
    val isSystem: Boolean
)

/**
 * Defines the class `CreateRoleRequest`, the contract implemented by this source unit.
 */
data class CreateRoleRequest(
    val name: String,
    val description: String? = null,
    val abilities: List<String>,
    val isDefault: Boolean = false
)

/**
 * Defines the class `UpdateRoleRequest`, the contract implemented by this source unit.
 */
data class UpdateRoleRequest(
    val name: String? = null,
    val description: String? = null,
    val abilities: List<String>? = null,
    val isDefault: Boolean? = null
)

/**
 * Defines the class `PermissionCheckRequest`, the contract implemented by this source unit.
 */
data class PermissionCheckRequest(
    val abilities: List<String>
)

/**
 * Defines the class `PermissionCheckResponse`, the contract implemented by this source unit.
 */
data class PermissionCheckResponse(
    val granted: List<String>,
    val denied: List<String>
)
