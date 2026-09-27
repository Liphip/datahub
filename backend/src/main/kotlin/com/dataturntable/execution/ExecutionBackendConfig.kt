package com.dataturntable.execution

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Selects the execution plane per deployment without leaking backend details to callers. */
@ApplicationScoped
class ExecutionBackendConfig(
    @ConfigProperty(name = "dataturntable.execution.backend", defaultValue = "docker")
    configuredBackend: String
) {
    val backend = configuredBackend.trim().lowercase()

    init {
        require(backend in setOf("docker", "kubernetes")) {
            "dataturntable.execution.backend must be docker or kubernetes"
        }
    }
}
