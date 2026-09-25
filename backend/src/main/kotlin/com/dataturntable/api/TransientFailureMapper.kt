package com.dataturntable.api

import com.dataturntable.engine.isTransientInfrastructureFailure
import io.quarkus.narayana.jta.QuarkusTransactionException
import jakarta.persistence.PersistenceException
import jakarta.transaction.RollbackException
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Lost database connections (e.g. during a PostgreSQL failover) are reported as
 * 503 + Retry-After so that clients resend instead of treating the request as failed.
 */
class TransientFailureMapper {

    private val log = Logger.getLogger(TransientFailureMapper::class.java)

    @ServerExceptionMapper
    fun persistence(e: PersistenceException): Response = map(e)

    @ServerExceptionMapper
    fun rollback(e: RollbackException): Response = map(e)

    @ServerExceptionMapper
    fun transaction(e: QuarkusTransactionException): Response = map(e)

    private fun map(e: Exception): Response {
        // A rolled-back transaction left nothing behind, so the client can safely resend.
        val rolledBack = generateSequence<Throwable>(e) { it.cause }.take(20).any { it is RollbackException }
        if (rolledBack || isTransientInfrastructureFailure(e)) {
            log.warnf("Request failed on unavailable infrastructure: %s", e.message)
            return Response.status(503).header("Retry-After", 5)
                .entity(mapOf("error" to "Service temporarily unavailable, retry later")).build()
        }
        log.error("Unhandled persistence error", e)
        return Response.serverError().entity(mapOf("error" to "Internal server error")).build()
    }
}
