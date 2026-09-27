package com.dataturntable.events

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class EventInboxService {
    @PersistenceContext lateinit var em: EntityManager

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    lateinit var dbKind: String

    /** Returns true exactly once per event ID in the durable inbox. */
    fun claim(eventId: UUID): Boolean {
        if (dbKind == "h2") {
            // H2 test schema lacks PostgreSQL ON CONFLICT; production always uses the atomic path below.
            // A caught SQLGrammarException would still leave the surrounding JTA transaction
            // marked rollback-only, so the dialect is checked upfront instead of relying on catch.
            return if (em.find(EventInbox::class.java, eventId) != null) {
                false
            } else {
                em.persist(EventInbox().apply { this.eventId = eventId })
                em.flush()
                true
            }
        }
        return em.createNativeQuery("INSERT INTO event_inbox(event_id, received_at) VALUES (:eventId, :receivedAt) ON CONFLICT (event_id) DO NOTHING")
            .setParameter("eventId", eventId)
            .setParameter("receivedAt", Instant.now())
            .executeUpdate() == 1
    }
}
