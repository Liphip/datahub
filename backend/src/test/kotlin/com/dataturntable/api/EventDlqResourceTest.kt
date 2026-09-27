package com.dataturntable.api

import com.dataturntable.events.EventOutbox
import com.dataturntable.events.EventOutboxStatus
import com.dataturntable.audit.AuditEvent
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class EventDlqResourceTest {
    @PersistenceContext lateinit var em: EntityManager

    @Test
    fun `platform administrator can inspect and explicitly retry a dead-letter event`() {
        val eventId = QuarkusTransaction.requiringNew().call {
            EventOutbox().apply {
                topic = "dataturntable.pipelines.input"
                partitionKey = "pipeline-${UUID.randomUUID()}"
                payload = "{\"eventId\":\"${UUID.randomUUID()}\",\"authorization\":\"Bearer dlq-secret-token\"}"
                status = EventOutboxStatus.DEAD_LETTERED
                attempts = 8
                lastError = "broker unavailable"
            }.also { em.persist(it); em.flush() }.id!!
        }
        val token = TestAuthHelpers.createAdminToken()

        given().header("Authorization", "Bearer $token")
        .`when`().get("/api/internal/v1/events/dlq")
        .then().statusCode(200)
            .body("find { it.id == '$eventId' }.attempts", org.hamcrest.Matchers.equalTo(8))
            .body("find { it.id == '$eventId' }.payload", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("dlq-secret-token")))

        given().header("Authorization", "Bearer $token")
        .`when`().post("/api/internal/v1/events/dlq/$eventId/retry")
        .then().statusCode(200)
            .body("attempts", org.hamcrest.Matchers.equalTo(0))
            .body("lastError", org.hamcrest.Matchers.nullValue())

        QuarkusTransaction.requiringNew().run {
            val event = em.find(EventOutbox::class.java, eventId)
            assertNotNull(event)
            org.junit.jupiter.api.Assertions.assertEquals(EventOutboxStatus.PENDING, event.status)
            val audit = em.createQuery(
                "SELECT a FROM AuditEvent a WHERE a.action = :action AND a.resourceId = :resourceId",
                AuditEvent::class.java
            )
                .setParameter("action", "event.dlq.retry")
                .setParameter("resourceId", eventId.toString())
                .singleResult
            org.junit.jupiter.api.Assertions.assertEquals("event_outbox", audit.resourceType)
        }
    }
}
