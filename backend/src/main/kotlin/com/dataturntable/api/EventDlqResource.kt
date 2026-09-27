package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.audit.AuditService
import com.dataturntable.events.EventOutbox
import com.dataturntable.events.EventOutboxService
import com.dataturntable.events.ConsumerDeadLetterService
import com.dataturntable.security.SensitiveDataRedactor
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/** Platform-only inspection and explicit replay of finite-retry broker handoff failures. */
@Path("/api/internal/v1/events/dlq")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
class EventDlqResource {
    @Inject lateinit var jwt: JsonWebToken
    @Inject lateinit var outbox: EventOutboxService
    @Inject lateinit var consumerDeadLetters: ConsumerDeadLetterService
    @Inject lateinit var audit: AuditService
    @Inject lateinit var redactor: SensitiveDataRedactor
    @PersistenceContext lateinit var em: EntityManager

    @GET
    fun list(@QueryParam("limit") limit: Int?): Response {
        requirePlatformAdmin()?.let { return it }
        return Response.ok(outbox.deadLetters(limit ?: 100).map(::toMap)).build()
    }

    @GET
    @Path("/consumer")
    fun listConsumer(@QueryParam("limit") limit: Int?): Response {
        requirePlatformAdmin()?.let { return it }
        return Response.ok(consumerDeadLetters.list(limit ?: 100).map {
            mapOf("id" to it.id, "channel" to it.channel, "payload" to it.payload, "error" to it.error, "createdAt" to it.createdAt)
        }).build()
    }

    @POST
    @Path("/{id}/retry")
    fun retry(@PathParam("id") rawId: String): Response {
        val actor = currentPlatformAdmin() ?: return Response.status(403).entity(mapOf("error" to "Platform administrator access required")).build()
        val id = runCatching { UUID.fromString(rawId) }.getOrNull()
            ?: return Response.status(400).entity(mapOf("error" to "Invalid event id")).build()
        val event = outbox.retryDeadLetter(id)
            ?: return Response.status(404).entity(mapOf("error" to "Dead-letter event not found")).build()
        audit.record(actor.id, null, "event.dlq.retry", "event_outbox", event.id.toString(), mapOf("topic" to event.topic))
        return Response.ok(toMap(event)).build()
    }

    private fun requirePlatformAdmin(): Response? {
        val user = currentPlatformAdmin()
        return if (user != null) null else Response.status(403).entity(mapOf("error" to "Platform administrator access required")).build()
    }

    private fun currentPlatformAdmin(): User? = jwt.subject
        ?.let { runCatching { em.find(User::class.java, UUID.fromString(it)) }.getOrNull() }
        ?.takeIf(User::isSuperAdmin)

    private fun toMap(event: EventOutbox) = mapOf(
        "id" to event.id,
        "topic" to event.topic,
        "partitionKey" to event.partitionKey,
        // Dead-letter inspection is operationally useful, but must not turn a
        // malformed producer record into a secret-disclosure endpoint.
        "payload" to redactor.text(event.payload),
        "attempts" to event.attempts,
        "lastError" to redactor.text(event.lastError),
        "createdAt" to event.createdAt,
        "nextAttemptAt" to event.nextAttemptAt
    )
}
