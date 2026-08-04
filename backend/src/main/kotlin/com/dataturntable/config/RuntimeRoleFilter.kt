package com.dataturntable.config

import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider

@Provider
@Priority(Priorities.AUTHORIZATION)
/**
 * Defines the class `RuntimeRoleFilter`, the contract implemented by this source unit.
 */
class RuntimeRoleFilter : ContainerRequestFilter {

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.path.trimStart('/')
        if (path.startsWith("q/")) return
        if (path.isBlank()) return

        val allowed = when {
            path.startsWith("api/internal/") -> runtimeRoleConfig.isControlApi()
            path.startsWith("api/ingest/") -> runtimeRoleConfig.isIngestEdge()
            path.startsWith("api/v") -> runtimeRoleConfig.isPublicApi()
            else -> true
        }

        if (!allowed) {
            requestContext.abortWith(
                Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Endpoint not available in runtime role ${runtimeRoleConfig.role.name.lowercase()}"))
                    .build()
            )
        }
    }
}
