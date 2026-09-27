package com.dataturntable.audit

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class AuditServiceTest {
    @Inject lateinit var audit: AuditService
    @Inject lateinit var em: EntityManager

    @Test
    @Transactional
    fun `audit records redact values with sensitive names`() {
        audit.record(null, null, "audit.test.redaction", "secret", "secret-1", mapOf("name" to "api-token", "value" to "never-store-this", "nested" to mapOf("password" to "also-secret")))
        em.flush()

        val event = em.createQuery("SELECT a FROM AuditEvent a WHERE a.action = :action", AuditEvent::class.java)
            .setParameter("action", "audit.test.redaction")
            .singleResult
        assertTrue(event.metadata!!.contains("[REDACTED]"))
        assertFalse(event.metadata!!.contains("never-store-this"))
        assertFalse(event.metadata!!.contains("also-secret"))
    }
}
