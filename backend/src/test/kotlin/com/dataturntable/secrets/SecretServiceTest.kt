package com.dataturntable.secrets

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class SecretServiceTest {
    @Inject lateinit var secrets: SecretService

    @Test
    @Transactional
    fun `secret rotation preserves immutable historical values`() {
        val tenant = UUID.randomUUID()
        val first = secrets.create(tenant, "connector.api-token", "initial-value")
        val second = secrets.rotate(tenant, first.id, "rotated-value")

        assertEquals(1, first.version)
        assertEquals(2, second.version)
        assertEquals("initial-value", secrets.resolve(tenant, first.id, 1))
        assertEquals("rotated-value", secrets.resolve(tenant, first.id))
        assertEquals(listOf(second), secrets.references(tenant))
    }

    @Test
    @Transactional
    fun `secret values cannot be resolved across tenants`() {
        val owner = UUID.randomUUID()
        val secret = secrets.create(owner, "webhook.token", "do-not-leak")

        assertThrows(IllegalArgumentException::class.java) {
            secrets.resolve(UUID.randomUUID(), secret.id)
        }
    }
}
