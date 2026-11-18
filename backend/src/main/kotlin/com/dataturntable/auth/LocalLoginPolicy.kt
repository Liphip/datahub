package com.dataturntable.auth

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.UUID

/** Password authentication policy; external identities still use explicit local account links. */
@Startup
@ApplicationScoped
class LocalLoginPolicy(
    @ConfigProperty(name = "dataturntable.auth.local-login-mode", defaultValue = "all") mode: String,
    @ConfigProperty(name = "dataturntable.auth.break-glass-user-ids") accounts: Optional<String>,
) {
    private val restricted: Boolean
    private val allowed: Set<UUID>

    init {
        require(mode in setOf("all", "break-glass-only")) { "local-login-mode must be all or break-glass-only" }
        restricted = mode == "break-glass-only"
        allowed = accounts.orElse("").split(',').filter { it.isNotBlank() }.map {
            val text = it.trim()
            val id = runCatching { UUID.fromString(text) }.getOrNull()
            require(id != null && id.toString().equals(text, ignoreCase = true)) {
                "break-glass-user-ids must contain complete UUIDs"
            }
            id
        }.toSet()
        require(!restricted || allowed.isNotEmpty()) { "break-glass-only requires at least one designated account UUID" }
    }

    fun allows(userId: UUID): Boolean = !restricted || userId in allowed
}
