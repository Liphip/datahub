package com.dataturntable.security

import com.dataturntable.auth.ApiKey
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@QuarkusTest
class IngestAuthorizationServiceTest {
    @Inject
    lateinit var authorization: IngestAuthorizationService

    @PersistenceContext
    lateinit var em: EntityManager

    @Test
    @Transactional
    fun `ingestion key is restricted to its tenant and exact pipeline`() {
        val tenantA = Tenant().apply { name = "ingest-a-${UUID.randomUUID()}"; persist() }
        val tenantB = Tenant().apply { name = "ingest-b-${UUID.randomUUID()}"; persist() }
        val allowedPipeline = UUID.randomUUID()
        val key = "test-ingestion-secret"
        ApiKey().apply {
            tenant = tenantA
            name = "pipeline-key-${UUID.randomUUID()}"
            keyHash = sha256(key)
            abilities = "[\"ingest:pipeline:$allowedPipeline\"]"
            persist()
        }
        em.flush()

        assertTrue(authorization.authorize(key, tenantA, "pipeline", allowedPipeline))
        assertFalse(authorization.authorize(key, tenantA, "pipeline", UUID.randomUUID()))
        assertFalse(authorization.authorize(key, tenantB, "pipeline", allowedPipeline))
        assertFalse(authorization.authorize("wrong-secret", tenantA, "pipeline", allowedPipeline))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
