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
        // Constraint violations are the client's input, not an outage: a resend would fail the same way.
        val sqlState = generateSequence<Throwable>(e) { it.cause }.take(20)
            .filterIsInstance<java.sql.SQLException>().firstNotNullOfOrNull { it.sqlState }
        when (sqlState) {
            "23505" -> return Response.status(409).entity(mapOf("error" to "An entry with this name or key already exists")).build()
            "23503" -> return Response.status(409).entity(mapOf("error" to "The entry is referenced by, or refers to, another entry")).build()
            "23502", "23514" -> return Response.status(400).entity(mapOf("error" to "The request violates a data constraint")).build()
        }
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
