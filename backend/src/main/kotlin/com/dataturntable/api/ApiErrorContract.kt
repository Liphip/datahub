package com.dataturntable.api

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.UUID

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, Any?>? = null,
    /** @deprecated Clients should use message; retained during the API-contract migration. */
    val error: String = message
)

@Provider
@Priority(Priorities.USER)
class RequestIdFilter : ContainerRequestFilter, ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext) {
        val requestId = UUID.randomUUID().toString()
        requestContext.setProperty(REQUEST_ID, requestId)
        CURRENT_REQUEST_ID.set(requestId)
    }

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        CURRENT_REQUEST_ID.remove()
    }

    companion object {
        const val REQUEST_ID = "dataturntable.request-id"
        private val CURRENT_REQUEST_ID = ThreadLocal<String?>()

        fun currentRequestId(): String? = CURRENT_REQUEST_ID.get()
    }
}

/** Converts all REST errors to a stable, safe public contract. */
@Provider
@Priority(Priorities.USER)
class ApiErrorResponseFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val requestId = requestContext.getProperty(RequestIdFilter.REQUEST_ID) as? String ?: UUID.randomUUID().toString()
        responseContext.headers.putSingle("X-Request-Id", requestId)
        if (responseContext.status < 400 || responseContext.entity is ApiError) return

        val status = responseContext.status
        val existing = responseContext.entity as? Map<*, *>
        val suppliedMessage = existing?.get("error")?.toString()
        val message = if (status >= 500) "An unexpected error occurred" else suppliedMessage ?: defaultMessage(status)
        responseContext.entity = ApiError(
            code = codeFor(status),
            message = message,
            requestId = requestId,
            details = safeDetails(existing, status)
        )
    }

    private fun safeDetails(entity: Map<*, *>?, status: Int): Map<String, Any?>? {
        if (status >= 500 || entity == null) return null
        val details = entity.entries
            .filter { (key, _) -> key?.toString() !in setOf("error", "stackTrace", "exception", "cause") }
            .associate { (key, value) -> key.toString() to value }
        return details.takeIf { it.isNotEmpty() }
    }

    private fun codeFor(status: Int) = when (status) {
        400 -> "VALIDATION_ERROR"
        401 -> "UNAUTHENTICATED"
        403 -> "FORBIDDEN"
        404 -> "NOT_FOUND"
        409 -> "CONFLICT"
        429 -> "RATE_LIMITED"
        502, 503, 504 -> "DEPENDENCY_UNAVAILABLE"
        else -> "INTERNAL_ERROR"
    }

    private fun defaultMessage(status: Int) = when (status) {
        401 -> "Authentication is required"
        403 -> "You are not permitted to perform this operation"
        404 -> "The requested resource was not found"
        else -> "The request could not be completed"
    }
}

/**
 * Applies baseline browser-hardening headers (NFR-006) to every response. This backend is a pure
 * JSON API in production — it never renders application HTML itself (the SPA is a separate static
 * build) — so the CSP is deliberately maximal (`default-src 'none'`) rather than tuned for a page
 * that needs scripts/styles/images. The one exception is Swagger UI, which Quarkus serves from
 * this same process but only outside `%prod` by default (`quarkus.swagger-ui.always-include`),
 * so it never reaches this filter in a real deployment.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
class SecurityHeadersFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val headers = responseContext.headers
        headers.putSingle("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        headers.putSingle("X-Frame-Options", "DENY")
        headers.putSingle("X-Content-Type-Options", "nosniff")
        headers.putSingle("Referrer-Policy", "no-referrer")
        headers.putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        headers.putSingle("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
    }
}

@Provider
class UnhandledExceptionMapper : ExceptionMapper<Throwable> {
    @Context
    lateinit var requestContext: ContainerRequestContext

    override fun toResponse(exception: Throwable): Response {
        if (exception is WebApplicationException) return exception.response
        val requestId = requestContext.getProperty(RequestIdFilter.REQUEST_ID) as? String ?: UUID.randomUUID().toString()
        LOG.error("Unhandled exception for request $requestId", exception)
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .header("X-Request-Id", requestId)
            .entity(ApiError("INTERNAL_ERROR", "An unexpected error occurred", requestId))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(UnhandledExceptionMapper::class.java)
    }
}
