package com.dataturntable.metadata

import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Regression coverage for the four metadata entities found missing their migration-declared
 * `UNIQUE` constraint on `@Table` (see `alert.EntityUniqueConstraintTest` for the full story):
 * `MetadataType`, `MetadataIdentifier`, `MetadataAttributeVersion`, `MetadataRelation`.
 */
@QuarkusTest
class MetadataEntityConstraintTest {

    @Inject lateinit var em: EntityManager

    private fun newTenant(): Tenant = Tenant().apply { name = "Metadata Constraint Test ${UUID.randomUUID()}" }

    private fun newType(tenant: Tenant, key: String): MetadataType = MetadataType().apply {
        tenantId = tenant.id; typeKey = key; name = key
    }

    private fun newObject(tenant: Tenant, type: MetadataType): MetadataObject = MetadataObject().apply {
        tenantId = tenant.id; typeId = type.id; name = "Object ${UUID.randomUUID()}"
    }

    @Test
    fun `metadata_types rejects a second type with the same tenant and type key`() {
        val key = "duplicate-key-${UUID.randomUUID()}"
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                em.persist(newType(tenant, key))
                em.persist(newType(tenant, key))
                em.flush()
            }
        }
    }

    @Test
    fun `metadata_identifiers rejects a second identifier with the same tenant, scheme, value and valid_from`() {
        val validFrom = Instant.parse("2024-01-01T00:00:00Z")
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val type = newType(tenant, "meter-${UUID.randomUUID()}")
                em.persist(type)
                val obj = newObject(tenant, type)
                em.persist(obj)
                em.persist(MetadataIdentifier().apply {
                    tenantId = tenant.id; objectId = obj.id; scheme = "malo"; this.value = "same-value"; this.validFrom = validFrom
                })
                em.persist(MetadataIdentifier().apply {
                    tenantId = tenant.id; objectId = obj.id; scheme = "malo"; this.value = "same-value"; this.validFrom = validFrom
                })
                em.flush()
            }
        }
    }

    @Test
    fun `metadata_attributes rejects a second version with the same object and valid_from`() {
        val validFrom = Instant.parse("2024-01-01T00:00:00Z")
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val type = newType(tenant, "device-${UUID.randomUUID()}")
                em.persist(type)
                val obj = newObject(tenant, type)
                em.persist(obj)
                em.persist(MetadataAttributeVersion().apply { objectId = obj.id; this.validFrom = validFrom })
                em.persist(MetadataAttributeVersion().apply { objectId = obj.id; this.validFrom = validFrom })
                em.flush()
            }
        }
    }

    @Test
    fun `metadata_relations rejects a second relation with the same endpoints, relation and valid_from`() {
        val validFrom = Instant.parse("2024-01-01T00:00:00Z")
        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                val tenant = newTenant()
                em.persist(tenant)
                val type = newType(tenant, "location-${UUID.randomUUID()}")
                em.persist(type)
                val from = newObject(tenant, type)
                val to = newObject(tenant, type)
                em.persist(from)
                em.persist(to)
                em.persist(MetadataRelation().apply {
                    tenantId = tenant.id; fromObjectId = from.id; relation = "market_location"; toObjectId = to.id; this.validFrom = validFrom
                })
                em.persist(MetadataRelation().apply {
                    tenantId = tenant.id; fromObjectId = from.id; relation = "market_location"; toObjectId = to.id; this.validFrom = validFrom
                })
                em.flush()
            }
        }
    }
}
