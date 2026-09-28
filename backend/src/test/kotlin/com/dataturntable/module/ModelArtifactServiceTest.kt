package com.dataturntable.module

import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Service-level coverage of [ModelArtifactService] (MOD-15/16/18) that needs no Docker daemon,
 * unlike [com.dataturntable.engine.ModelArtifactRoundTripTest] which exercises the Python harness
 * end to end. Versioning, encryption round-trip, and the cache invalidation MOD-18 relies on.
 */
@QuarkusTest
class ModelArtifactServiceTest {

    @Inject lateinit var service: ModelArtifactService
    @Inject lateinit var em: EntityManager

    private fun newModule(): UUID = QuarkusTransaction.requiringNew().call<UUID> {
        val tenant = Tenant().apply { name = "Model Artifact Service Test ${UUID.randomUUID()}" }
        em.persist(tenant)
        val module = CodeModule().apply { this.tenant = tenant; this.name = "m"; this.moduleType = ModuleType.PROCESSING }
        em.persist(module)
        em.flush()
        module.id!!
    }

    @Test
    fun `saving under the same name creates a new version rather than overwriting`() {
        val tenantId = UUID.randomUUID()
        val moduleId = newModule()
        val v1 = QuarkusTransaction.requiringNew().call { service.save(tenantId, moduleId, "forecast", "YWJj") }
        val v2 = QuarkusTransaction.requiringNew().call { service.save(tenantId, moduleId, "forecast", "ZGVm") }
        assertEquals(1, v1.version)
        assertEquals(2, v2.version)
        assertEquals("ZGVm", service.loadLatest(moduleId, "forecast"))
        assertEquals("YWJj", service.loadVersion(moduleId, "forecast", 1))
        assertEquals("ZGVm", service.loadVersion(moduleId, "forecast", 2))
    }

    @Test
    fun `loading a name never saved returns null`() {
        assertNull(service.loadLatest(newModule(), "never-saved"))
    }

    @Test
    fun `a save invalidates the preload cache immediately, without waiting for the TTL`() {
        val tenantId = UUID.randomUUID()
        val moduleId = newModule()
        assertTrue(service.preloadLatestForModule(moduleId).isEmpty())
        QuarkusTransaction.requiringNew().call { service.save(tenantId, moduleId, "m1", "AQ==") }
        // If the cache were not invalidated on save, this would still see the empty map cached above.
        assertEquals("AQ==", service.preloadLatestForModule(moduleId)["m1"])
        QuarkusTransaction.requiringNew().call { service.save(tenantId, moduleId, "m1", "Ag==") }
        assertEquals("Ag==", service.preloadLatestForModule(moduleId)["m1"])
    }

    @Test
    fun `different names under the same module are preloaded independently`() {
        val tenantId = UUID.randomUUID()
        val moduleId = newModule()
        QuarkusTransaction.requiringNew().call { service.save(tenantId, moduleId, "a", "MQ==") }
        QuarkusTransaction.requiringNew().call { service.save(tenantId, moduleId, "b", "Mg==") }
        val preloaded = service.preloadLatestForModule(moduleId)
        assertEquals("MQ==", preloaded["a"])
        assertEquals("Mg==", preloaded["b"])
    }
}
