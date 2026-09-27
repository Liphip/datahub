package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.events.ConsumerDeadLetterService
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/**
 * Platform-only inspection of pipeline messages the executor could not use (unparseable, or
 * addressed to a pipeline that no longer exists). Payloads are redacted when recorded. Records
 * that failed for other reasons are kept on the Kafka dead-letter topic
 * `dataturntable.pipelines.input.dlq`.
 */
@Path("/api/internal/v1/events/dlq")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
class EventDlqResource {
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var consumerDeadLetters: ConsumerDeadLetterService
    @PersistenceContext lateinit var em: EntityManager

    @GET
    fun list(@QueryParam("limit") limit: Int?): Response = listConsumer(limit)

    @GET
    @Path("/consumer")
    fun listConsumer(@QueryParam("limit") limit: Int?): Response {
        requirePlatformAdmin()?.let { return it }
        return Response.ok(consumerDeadLetters.list(limit ?: 100).map {
            mapOf("id" to it.id, "channel" to it.channel, "payload" to it.payload, "error" to it.error, "createdAt" to it.createdAt)
        }).build()
    }

    private fun requirePlatformAdmin(): Response? =
        if (currentPlatformAdmin() != null) null
        else Response.status(403).entity(mapOf("error" to "Platform administrator access required")).build()

    private fun currentPlatformAdmin(): User? = jwt.subject
        ?.let { runCatching { em.find(User::class.java, UUID.fromString(it)) }.getOrNull() }
        ?.takeIf(User::isSuperAdmin)
}
