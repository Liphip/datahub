package com.dataturntable.alert

import com.dataturntable.PanacheBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A channel action belonging to an alert rule.  Rules created before V20 still
 * use AlertRule.channel and are read as a single implicit action. */
@Entity
@Table(name = "alert_rule_actions")
open class AlertRuleAction : PanacheBase() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    var rule: AlertRule? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    var channel: AlertChannel? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
