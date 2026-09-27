package com.dataturntable.security

import com.dataturntable.auth.ApiKey
import com.dataturntable.tenant.Tenant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class IngestAuthorizationService {
    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Authorizes an ingestion credential for exactly one tenant-bound resource. Keys are stored
     * as SHA-256 digests so raw secrets are never persisted or emitted in logs.
     */
    fun authorize(rawKey: String?, tenant: Tenant?, resourceType: String, resourceId: UUID): Boolean {
        if (rawKey.isNullOrBlank() || tenant?.id == null) return false
        val digest = sha256(rawKey)
        val keys = em.createQuery(
            "SELECT k FROM ApiKey k WHERE k.tenant.id = :tenantId AND k.isActive = true AND (k.expiresAt IS NULL OR k.expiresAt > :now)",
            ApiKey::class.java
        )
            .setParameter("tenantId", tenant.id)
            .setParameter("now", Instant.now())
            .resultList
        val key = keys.firstOrNull { stored ->
            val storedHash = stored.keyHash ?: return@firstOrNull false
            MessageDigest.isEqual(storedHash.toByteArray(StandardCharsets.UTF_8), digest.toByteArray(StandardCharsets.UTF_8))
        } ?: return false

        val required = "ingest:$resourceType:$resourceId"
        if (!AbilityEvaluator.hasAbility(key.abilities, required)) return false
        key.lastUsedAt = Instant.now()
        return true
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
