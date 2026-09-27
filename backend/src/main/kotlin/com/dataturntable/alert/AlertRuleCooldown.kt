package com.dataturntable.alert

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Durable coordination row for a rule's shared delivery cooldown. */
@Entity
@Table(name = "alert_rule_cooldowns")
open class AlertRuleCooldown {
    @Id
    @Column(name = "rule_id")
    var ruleId: UUID? = null

    @Column(name = "last_fired_at", nullable = false)
    var lastFiredAt: Instant = Instant.EPOCH
}
