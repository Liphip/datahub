package com.dataturntable.alert

import com.dataturntable.secrets.SecretService
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class AlertChannelSecretReferenceResolverTest {
    @Inject lateinit var resolver: AlertChannelSecretReferenceResolver
    @Inject lateinit var secrets: SecretService
    @Inject lateinit var em: EntityManager

    @Test
    @Transactional
    fun `alert delivery config resolves tenant secret references only in memory`() {
        val tenant = Tenant().apply { name = "alert secret tenant"; createdAt = Instant.now(); updatedAt = Instant.now() }
        em.persist(tenant)
        em.flush()
        val reference = secrets.create(tenant.id!!, "alert.webhook-token", "delivery-only-token")
        val channel = AlertChannel().apply {
            this.tenant = tenant
            name = "secret-backed webhook"
            channelType = AlertChannelType.WEBHOOK
        }
        val config = mapOf(
            "url" to "https://alerts.example.test/hook",
            "secretRefs" to mapOf("authorization" to mapOf("id" to reference.id.toString(), "version" to reference.version))
        )

        val resolved = resolver.resolve(channel, config)

        assertEquals("delivery-only-token", resolved["authorization"])
        assertFalse(resolved.containsKey("secretRefs"))
    }
}
