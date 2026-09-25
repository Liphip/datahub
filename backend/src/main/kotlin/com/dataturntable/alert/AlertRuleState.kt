package com.dataturntable.alert

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Composite key of [AlertRuleState]: one state row per rule node and series. */
data class AlertRuleStateId(
    var pipelineId: UUID? = null,
    var nodeId: String? = null,
    var seriesKey: String? = null,
) : Serializable

/**
 * Persistent state of a built-in alert rule. Kept in the database so that every
 * pipeline-executor replica sees the same state and a failover loses nothing.
 */
@Entity
@Table(name = "alert_rule_state")
@IdClass(AlertRuleStateId::class)
open class AlertRuleState {

    @Id
    @Column(name = "pipeline_id")
    var pipelineId: UUID? = null

    @Id
    @Column(name = "node_id", length = 255)
    var nodeId: String? = null

    /** Value of the rule's series field, or "" when the rule tracks the whole stream. */
    @Id
    @Column(name = "series_key", length = 512)
    var seriesKey: String? = null

    /** rate_of_change: previous value and its timestamp. */
    @Column(name = "last_value")
    var lastValue: Double? = null

    @Column(name = "last_ts")
    var lastTs: Instant? = null

    /** time_window: JSON array of [epochMillis, value] pairs inside the window. */
    @Column(name = "window_samples", columnDefinition = "text")
    var windowSamples: String? = null

    /** absence: when data was last seen, and whether the current silence was already alerted. */
    @Column(name = "last_seen")
    var lastSeen: Instant? = null

    @Column(name = "absence_alerted", nullable = false)
    var absenceAlerted: Boolean = false

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
