package com.dataturntable.alert

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Coordinates alert rule cooldowns through a row-level database lock. Unlike a
 * history lookup, this decision is atomic across Alert Service replicas.
 */
@ApplicationScoped
class AlertCooldownService {
    @PersistenceContext lateinit var em: EntityManager

    @Transactional
    fun initialize(ruleId: UUID?) {
        if (ruleId == null || em.find(AlertRuleCooldown::class.java, ruleId) != null) return
        em.persist(AlertRuleCooldown().apply { this.ruleId = ruleId })
    }

    @Transactional
    fun tryAcquire(ruleId: UUID?, cooldownSeconds: Int, now: Instant? = null): Boolean {
        if (ruleId == null || cooldownSeconds <= 0) return true
        val decisionTime = now ?: databaseNow()
        // All new and migrated rules have a coordination row. The fallback makes
        // recovery from a partial historical migration safe without using process memory.
        var cooldown = em.find(AlertRuleCooldown::class.java, ruleId, LockModeType.PESSIMISTIC_WRITE)
        if (cooldown == null) {
            cooldown = AlertRuleCooldown().apply { this.ruleId = ruleId; lastFiredAt = decisionTime }
            em.persist(cooldown)
            return true
        }
        if (Duration.between(cooldown.lastFiredAt, decisionTime).seconds < cooldownSeconds) return false
        cooldown.lastFiredAt = decisionTime
        return true
    }

    private fun databaseNow(): Instant = when (val value = em.createNativeQuery("SELECT CURRENT_TIMESTAMP").singleResult) {
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        is Timestamp -> value.toInstant()
        else -> Instant.parse(value.toString())
    }
}
