package com.dataturntable.security

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window limit per ingestion credential. Off by default (0): fire-and-forget senders
 * cannot retry a refused record, so a limit trades their data for overload protection and
 * must be set deliberately (`dataturntable.ingest.rate-limit-per-minute`).
 */
@ApplicationScoped
class IngestRateLimiter {
    @ConfigProperty(name = "dataturntable.ingest.rate-limit-per-minute", defaultValue = "0")
    var configuredLimit: Int = 0

    /**
     * TEN-10: default ingest quota per tenant in records per minute, all credentials and
     * protocols of the tenant together; a tenant's own `ingestRecordsPerMinute` quota overrides
     * it. Off by default (0) for the same reason as the per-credential limit.
     */
    @ConfigProperty(name = "dataturntable.ingest.tenant-rate-limit-per-minute", defaultValue = "0")
    var configuredTenantLimit: Int = 0

    private data class Bucket(var startedAtMillis: Long, var count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(rawCredential: String?, limitPerMinute: Int = configuredLimit): Boolean {
        if (limitPerMinute <= 0) return true
        if (rawCredential.isNullOrBlank()) return false
        return tryAcquireFingerprint(fingerprint(rawCredential), limitPerMinute)
    }

    /**
     * Applies the limit using a SHA-256 credential fingerprint.  WebSocket
     * sessions retain only this non-secret value after their opening handshake.
     */
    fun tryAcquireFingerprint(fingerprint: String?, limitPerMinute: Int = configuredLimit): Boolean {
        if (limitPerMinute <= 0) return true
        if (fingerprint.isNullOrBlank()) return false
        val key = fingerprint
        val now = System.currentTimeMillis()
        val bucket = buckets.compute(key) { _, existing ->
            when {
                existing == null || now - existing.startedAtMillis >= 60_000 -> Bucket(now, 1)
                existing.count >= limitPerMinute -> existing.apply { count = limitPerMinute + 1 }
                else -> existing.apply { count += 1 }
            }
        }!!
        return bucket.count <= limitPerMinute
    }

    /**
     * Takes [records] from the tenant's quota for the current minute, all or nothing. Returns 0
     * when they fit, otherwise the seconds until the window ends — the `Retry-After` to answer
     * with. The count is kept per instance: with several ingest replicas each one enforces the
     * limit on the share of traffic it receives.
     */
    fun tenantRetryAfter(tenantId: java.util.UUID?, tenantLimit: Int?, records: Int = 1): Int {
        val limit = tenantLimit ?: configuredTenantLimit
        if (limit <= 0 || tenantId == null) return 0
        val now = System.currentTimeMillis()
        var retryAfter = 0
        buckets.compute("tenant:$tenantId") { _, existing ->
            val bucket = if (existing == null || now - existing.startedAtMillis >= 60_000) Bucket(now, 0) else existing
            if (bucket.count + records > limit) {
                retryAfter = (((bucket.startedAtMillis + 60_000 - now) + 999) / 1000).toInt().coerceAtLeast(1)
            } else {
                bucket.count += records
            }
            bucket
        }
        return retryAfter
    }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
