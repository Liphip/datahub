package com.dataturntable.security

import com.dataturntable.auth.ApiKey
import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class IngestAuthorizationServiceTest {
    @Inject
    lateinit var authorization: IngestAuthorizationService

    @Inject
    lateinit var em: EntityManager

    @Test
    fun `ingestion key is restricted to its tenant and exact pipeline`() {
        val allowedPipeline = UUID.randomUUID()
        val key = "test-ingestion-secret-${UUID.randomUUID()}"
        val (tenantA, tenantB, keyId) = QuarkusTransaction.requiringNew().call {
            val a = Tenant().apply { name = "ingest-a-${UUID.randomUUID()}"; persist() }
            val b = Tenant().apply { name = "ingest-b-${UUID.randomUUID()}"; persist() }
            val k = ApiKey().apply {
                tenant = a
                name = "pipeline-key-${UUID.randomUUID()}"
                keyHash = IngestAuthorizationService.sha256(key)
                abilities = "[\"ingest:pipeline:$allowedPipeline\"]"
                persist()
            }
            Triple(a.id!!, b.id!!, k.id!!)
        }

        assertTrue(authorization.authorize(key, tenantA, "pipeline", allowedPipeline))
        assertFalse(authorization.authorize(key, tenantA, "pipeline", UUID.randomUUID()))
        assertFalse(authorization.authorize(key, tenantB, "pipeline", allowedPipeline))
        assertFalse(authorization.authorize("wrong-secret", tenantA, "pipeline", allowedPipeline))

        // Revocation takes effect with the next refresh of the key cache.
        QuarkusTransaction.requiringNew().run { em.find(ApiKey::class.java, keyId).isActive = false }
        authorization.refresh()
        assertFalse(authorization.authorize(key, tenantA, "pipeline", allowedPipeline))
    }
}
