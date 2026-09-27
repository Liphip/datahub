package com.dataturntable.security

import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Fixed-window protection for the current monolith ingest edge. */
@ApplicationScoped
class IngestRateLimiter {
    private data class Bucket(var startedAtMillis: Long, var count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(rawCredential: String?, limitPerMinute: Int = 600): Boolean {
        if (rawCredential.isNullOrBlank()) return false
        return tryAcquireFingerprint(fingerprint(rawCredential), limitPerMinute)
    }

    /**
     * Applies the limit using a SHA-256 credential fingerprint.  WebSocket
     * sessions retain only this non-secret value after their opening handshake.
     */
    fun tryAcquireFingerprint(fingerprint: String?, limitPerMinute: Int = 600): Boolean {
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

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
