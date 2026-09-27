package com.dataturntable.security

import com.dataturntable.auth.ApiKey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks ingestion credentials (API keys) without a database round trip per record: keys are
 * cached by their SHA-256 digest and refreshed periodically, so ingestion keeps working for
 * known keys while the database is unavailable (fire-and-forget senders cannot retry). An
 * unknown digest is looked up once; revocations take effect within the refresh interval.
 */
@ApplicationScoped
class IngestAuthorizationService {

    private val log = Logger.getLogger(IngestAuthorizationService::class.java)

    @Inject
    lateinit var em: EntityManager

    private data class CachedKey(val id: UUID, val tenantId: UUID, val abilities: String, val expiresAt: Instant?)

    @Volatile private var keys: Map<String, CachedKey> = emptyMap()
    @Volatile private var loaded = false
    /** Digests known not to exist, so a flood of bad keys does not hit the database. */
    private val misses = ConcurrentHashMap<String, Instant>()
    /** Keys used since the last flush of `last_used_at`. */
    private val used = ConcurrentHashMap<UUID, Instant>()

    /**
     * Authorizes [rawKey] for exactly one tenant-bound resource (`ingest:<type>:<id>`).
     */
    fun authorize(rawKey: String?, tenantId: UUID?, resourceType: String, resourceId: UUID): Boolean {
        if (rawKey.isNullOrBlank() || tenantId == null) return false
        val digest = sha256(rawKey)
        val key = lookup(digest) ?: return false
        if (key.tenantId != tenantId) return false
        if (key.expiresAt != null && !key.expiresAt.isAfter(Instant.now())) return false
        if (!AbilityEvaluator.hasAbility(key.abilities, "ingest:$resourceType:$resourceId")) return false
        used[key.id] = Instant.now()
        return true
    }

    private fun lookup(digest: String): CachedKey? {
        if (!loaded) runCatching { refresh() }
        keys[digest]?.let { return it }
        misses[digest]?.let { if (it.isAfter(Instant.now().minusSeconds(30))) return null }
        val found = runCatching {
            QuarkusTransaction.requiringNew().call {
                em.createQuery("SELECT k FROM ApiKey k WHERE k.keyHash = :h AND k.isActive = true", ApiKey::class.java)
                    .setParameter("h", digest).resultList.firstOrNull()?.let(::toCached)
            }
        }.getOrNull()
        if (found != null) {
            keys = keys + (digest to found)
            return found
        }
        if (misses.size < 10_000) misses[digest] = Instant.now()
        return null
    }

    private fun toCached(k: ApiKey) = k.tenant?.id?.let { CachedKey(k.id!!, it, k.abilities, k.expiresAt) }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledRefresh() {
        try {
            refresh()
            flushUsage()
        } catch (e: Exception) {
            log.debugf("API key refresh skipped: %s", e.message)
        }
    }

    fun refresh() {
        keys = QuarkusTransaction.requiringNew().call {
            em.createQuery("SELECT k FROM ApiKey k JOIN FETCH k.tenant WHERE k.isActive = true", ApiKey::class.java).resultList
                .mapNotNull { k -> toCached(k)?.let { k.keyHash!! to it } }.toMap()
        }
        misses.clear()
        loaded = true
    }

    private fun flushUsage() {
        if (used.isEmpty()) return
        val batch = HashMap(used)
        batch.keys.forEach { used.remove(it) }
        QuarkusTransaction.requiringNew().run {
            batch.forEach { (id, at) ->
                em.createQuery("UPDATE ApiKey k SET k.lastUsedAt = :at WHERE k.id = :id").setParameter("at", at).setParameter("id", id).executeUpdate()
            }
        }
    }

    companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
