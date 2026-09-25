package com.dataturntable.alert

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.abs

/** Built-in alert rule types that ALERT nodes can use without code (ALT-02). */
enum class AlertRuleType(val key: String) {
    THRESHOLD("threshold"),
    RATE_OF_CHANGE("rate_of_change"),
    TIME_WINDOW("time_window"),
    ABSENCE("absence");

    companion object {
        fun of(key: String?): AlertRuleType? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** Validated configuration of a built-in alert rule, as stored in the graph node's `alertRule`. */
data class AlertRuleConfig(
    val type: AlertRuleType,
    val field: String?,
    val operator: String,
    val value: Any?,
    val severity: String,
    val title: String?,
    val seriesField: String?,
    val timestampField: String?,
    val delta: Double?,
    val intervalSeconds: Long,
    val direction: String,
    val aggregation: String,
    val windowSeconds: Long,
    val timeoutSeconds: Long,
    val cooldownSeconds: Long?,
) {
    companion object {
        private val OPERATORS = setOf(">", ">=", "<", "<=", "==", "!=")
        private val AGGREGATIONS = setOf("avg", "min", "max", "sum", "count")
        private val DIRECTIONS = setOf("any", "up", "down")
        private val SEVERITIES = setOf("info", "warning", "error", "critical")

        /** Parses and validates a rule; throws [IllegalArgumentException] with a user-facing message. */
        fun parse(raw: Map<*, *>): AlertRuleConfig {
            fun str(key: String) = raw[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            fun num(key: String): Double? = when (val v = raw[key]) {
                null -> null
                is Number -> v.toDouble()
                else -> v.toString().trim().takeIf { it.isNotEmpty() }?.let {
                    it.toDoubleOrNull() ?: throw IllegalArgumentException("Alert rule '$key' must be a number")
                }
            }
            fun positiveSeconds(key: String, default: Long? = null): Long {
                val v = num(key) ?: default?.toDouble() ?: throw IllegalArgumentException("Alert rule requires '$key'")
                require(v > 0) { "Alert rule '$key' must be greater than 0" }
                return v.toLong()
            }

            val type = AlertRuleType.of(str("type"))
                ?: throw IllegalArgumentException("Alert rule type must be one of ${AlertRuleType.entries.joinToString { it.key }}")
            val field = str("field")
            if (type != AlertRuleType.ABSENCE) require(field != null) { "Alert rule '${type.key}' requires 'field'" }

            val operator = str("operator") ?: ">"
            require(operator in OPERATORS) { "Alert rule operator must be one of ${OPERATORS.joinToString(" ")}" }
            val value: Any? = when (type) {
                AlertRuleType.THRESHOLD, AlertRuleType.TIME_WINDOW -> {
                    when (val rawValue = raw["value"] ?: throw IllegalArgumentException("Alert rule '${type.key}' requires 'value'")) {
                        is Number -> rawValue.toDouble()
                        else -> rawValue.toString().trim().let { it.toDoubleOrNull() ?: it }
                    }
                }
                else -> null
            }
            if (value !is Double && type in setOf(AlertRuleType.THRESHOLD, AlertRuleType.TIME_WINDOW)) {
                require(operator == "==" || operator == "!=") { "Alert rule 'value' must be a number for operator $operator" }
            }

            val severity = (str("severity") ?: "warning").lowercase()
            require(severity in SEVERITIES) { "Alert rule severity must be one of ${SEVERITIES.joinToString()}" }
            val direction = (str("direction") ?: "any").lowercase()
            require(direction in DIRECTIONS) { "Alert rule direction must be one of ${DIRECTIONS.joinToString()}" }
            val aggregation = (str("aggregation") ?: "avg").lowercase()
            require(aggregation in AGGREGATIONS) { "Alert rule aggregation must be one of ${AGGREGATIONS.joinToString()}" }

            val delta = if (type == AlertRuleType.RATE_OF_CHANGE) {
                (num("delta") ?: throw IllegalArgumentException("Alert rule 'rate_of_change' requires 'delta'"))
                    .also { require(it >= 0) { "Alert rule 'delta' must not be negative" } }
            } else null

            return AlertRuleConfig(
                type = type,
                field = field,
                operator = operator,
                value = value,
                severity = severity,
                title = str("title"),
                seriesField = str("seriesField"),
                timestampField = str("timestampField"),
                delta = delta,
                intervalSeconds = if (type == AlertRuleType.RATE_OF_CHANGE) positiveSeconds("intervalSeconds", 60) else 60,
                direction = direction,
                aggregation = aggregation,
                windowSeconds = if (type == AlertRuleType.TIME_WINDOW) positiveSeconds("windowSeconds") else 0,
                timeoutSeconds = if (type == AlertRuleType.ABSENCE) positiveSeconds("timeoutSeconds") else 0,
                cooldownSeconds = num("cooldownSeconds")?.toLong()?.also { require(it >= 0) { "Alert rule 'cooldownSeconds' must not be negative" } },
            )
        }
    }
}

/** An alert produced by a built-in rule, ready for [AlertDispatcher.dispatchToChannel]. */
data class RuleAlert(
    val title: String,
    val message: String,
    val severity: String,
    val seriesKey: String,
    val payload: Map<String, Any?>,
)

/**
 * Evaluates built-in alert rules against the records reaching an ALERT node.
 * State (previous values, window samples, last-seen times) lives in [AlertRuleState]
 * and is updated under a row lock in its own transaction, so concurrent executor
 * replicas and failovers see consistent state.
 */
@ApplicationScoped
class AlertRuleEvaluator {

    @PersistenceContext
    lateinit var em: EntityManager

    private val objectMapper = ObjectMapper()

    /** Upper bound of samples kept per series for time_window rules. */
    private val maxWindowSamples = 10_000

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun evaluate(pipelineId: UUID, nodeId: String, rule: AlertRuleConfig, input: Any?, now: Instant = Instant.now()): List<RuleAlert> {
        val alerts = mutableListOf<RuleAlert>()
        for (record in records(input)) {
            val seriesKey = rule.seriesField?.let { resolve(record, it)?.toString() }.orEmpty()
            when (rule.type) {
                AlertRuleType.THRESHOLD -> evaluateThreshold(rule, record, seriesKey)?.let(alerts::add)
                AlertRuleType.RATE_OF_CHANGE -> evaluateRate(pipelineId, nodeId, rule, record, seriesKey, now)?.let(alerts::add)
                AlertRuleType.TIME_WINDOW -> evaluateWindow(pipelineId, nodeId, rule, record, seriesKey, now)?.let(alerts::add)
                AlertRuleType.ABSENCE -> markSeen(pipelineId, nodeId, seriesKey, now)
            }
        }
        return alerts
    }

    /**
     * Claims every absence rule state whose silence exceeds the timeout and has not been
     * alerted yet. The claim is an atomic conditional update, so with several replicas
     * exactly one of them reports each silence.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun claimAbsences(pipelineId: UUID, nodeId: String, rule: AlertRuleConfig, now: Instant): List<RuleAlert> {
        // Start watching streams that never delivered data: the first check opens the grace period.
        val tracked = em.createQuery(
            "SELECT COUNT(s) FROM AlertRuleState s WHERE s.pipelineId = :p AND s.nodeId = :n",
            java.lang.Long::class.java
        ).setParameter("p", pipelineId).setParameter("n", nodeId).singleResult.toLong()
        if (tracked == 0L) {
            markSeen(pipelineId, nodeId, "", now)
            return emptyList()
        }

        val cutoff = now.minusSeconds(rule.timeoutSeconds)
        val silent = em.createQuery(
            "SELECT s FROM AlertRuleState s WHERE s.pipelineId = :p AND s.nodeId = :n " +
                "AND s.absenceAlerted = false AND s.lastSeen < :cutoff",
            AlertRuleState::class.java
        ).setParameter("p", pipelineId).setParameter("n", nodeId).setParameter("cutoff", cutoff).resultList

        return silent.mapNotNull { state ->
            val claimed = em.createQuery(
                "UPDATE AlertRuleState s SET s.absenceAlerted = true, s.updatedAt = :now " +
                    "WHERE s.pipelineId = :p AND s.nodeId = :n AND s.seriesKey = :k " +
                    "AND s.absenceAlerted = false AND s.lastSeen < :cutoff"
            ).setParameter("now", now).setParameter("p", pipelineId).setParameter("n", nodeId)
                .setParameter("k", state.seriesKey).setParameter("cutoff", cutoff).executeUpdate()
            if (claimed != 1) return@mapNotNull null
            val series = state.seriesKey.orEmpty()
            val silenceSeconds = state.lastSeen?.let { now.epochSecond - it.epochSecond } ?: rule.timeoutSeconds
            RuleAlert(
                title = rule.title ?: "No data" + seriesSuffix(series),
                message = "No data received for $silenceSeconds s (timeout ${rule.timeoutSeconds} s)" + seriesSuffix(series),
                severity = rule.severity,
                seriesKey = series,
                payload = mapOf("series" to series.ifEmpty { null }, "lastSeen" to state.lastSeen?.toString(), "timeoutSeconds" to rule.timeoutSeconds),
            )
        }
    }

    // ── Rule types ────────────────────────────────────────────────────────────

    private fun evaluateThreshold(rule: AlertRuleConfig, record: Map<String, Any?>, seriesKey: String): RuleAlert? {
        val actual = resolve(record, rule.field!!) ?: return null
        if (!compare(actual, rule.operator, rule.value)) return null
        return RuleAlert(
            title = rule.title ?: "${rule.field} ${rule.operator} ${format(rule.value)}",
            message = "${rule.field} = ${format(actual)} (${rule.operator} ${format(rule.value)})" + seriesSuffix(seriesKey),
            severity = rule.severity,
            seriesKey = seriesKey,
            payload = record,
        )
    }

    private fun evaluateRate(
        pipelineId: UUID, nodeId: String, rule: AlertRuleConfig,
        record: Map<String, Any?>, seriesKey: String, now: Instant,
    ): RuleAlert? {
        val value = number(resolve(record, rule.field!!)) ?: return null
        val ts = timestamp(record, rule, now)
        val state = lockState(pipelineId, nodeId, seriesKey)
        val previous = state.lastValue
        val previousTs = state.lastTs
        // Late or duplicate records (at-least-once redelivery) do not move the state backwards.
        if (previousTs != null && !ts.isAfter(previousTs)) return null
        state.lastValue = value
        state.lastTs = ts
        state.updatedAt = now
        if (previous == null || previousTs == null) return null

        val seconds = (ts.toEpochMilli() - previousTs.toEpochMilli()) / 1000.0
        val change = (value - previous) * rule.intervalSeconds / seconds
        val exceeded = when (rule.direction) {
            "up" -> change > rule.delta!!
            "down" -> -change > rule.delta!!
            else -> abs(change) > rule.delta!!
        }
        if (!exceeded) return null
        return RuleAlert(
            title = rule.title ?: "${rule.field} changed by more than ${format(rule.delta)} per ${rule.intervalSeconds} s",
            message = "${rule.field} went from ${format(previous)} to ${format(value)} in ${format(seconds)} s " +
                "(${format(change)} per ${rule.intervalSeconds} s)" + seriesSuffix(seriesKey),
            severity = rule.severity,
            seriesKey = seriesKey,
            payload = record + mapOf("previousValue" to previous, "changePerInterval" to change),
        )
    }

    private fun evaluateWindow(
        pipelineId: UUID, nodeId: String, rule: AlertRuleConfig,
        record: Map<String, Any?>, seriesKey: String, now: Instant,
    ): RuleAlert? {
        val value = number(resolve(record, rule.field!!)) ?: return null
        val ts = timestamp(record, rule, now).toEpochMilli()
        val state = lockState(pipelineId, nodeId, seriesKey)
        val samples = state.windowSamples
            ?.let { objectMapper.readValue(it, object : TypeReference<MutableList<List<Double>>>() {}) }
            ?: mutableListOf()
        // Ignore exact duplicates (at-least-once redelivery).
        if (samples.none { it[0].toLong() == ts && it[1] == value }) samples += listOf(ts.toDouble(), value)
        val newest = samples.maxOf { it[0] }
        val kept = samples.filter { it[0] > newest - rule.windowSeconds * 1000 }.sortedBy { it[0] }.takeLast(maxWindowSamples)
        state.windowSamples = objectMapper.writeValueAsString(kept)
        state.updatedAt = now

        val values = kept.map { it[1] }
        val aggregate = when (rule.aggregation) {
            "min" -> values.min()
            "max" -> values.max()
            "sum" -> values.sum()
            "count" -> values.size.toDouble()
            else -> values.average()
        }
        if (!compare(aggregate, rule.operator, rule.value)) return null
        return RuleAlert(
            title = rule.title ?: "${rule.aggregation}(${rule.field}) over ${rule.windowSeconds} s ${rule.operator} ${format(rule.value)}",
            message = "${rule.aggregation}(${rule.field}) = ${format(aggregate)} over the last ${rule.windowSeconds} s " +
                "(${values.size} samples)" + seriesSuffix(seriesKey),
            severity = rule.severity,
            seriesKey = seriesKey,
            payload = record + mapOf("aggregate" to aggregate, "samples" to values.size),
        )
    }

    private fun markSeen(pipelineId: UUID, nodeId: String, seriesKey: String, now: Instant) {
        val state = lockState(pipelineId, nodeId, seriesKey)
        state.lastSeen = now
        state.absenceAlerted = false
        state.updatedAt = now
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Loads the state row with a write lock, creating it when missing. */
    private fun lockState(pipelineId: UUID, nodeId: String, seriesKey: String): AlertRuleState {
        val id = AlertRuleStateId(pipelineId, nodeId, seriesKey)
        em.find(AlertRuleState::class.java, id, LockModeType.PESSIMISTIC_WRITE)?.let { return it }
        // Insert-if-absent, then lock: safe when two replicas see a new series at the same time.
        em.createNativeQuery(
            "INSERT INTO alert_rule_state (pipeline_id, node_id, series_key, absence_alerted, updated_at) " +
                "VALUES (?1, ?2, ?3, false, ?4) ON CONFLICT DO NOTHING"
        ).setParameter(1, pipelineId).setParameter(2, nodeId).setParameter(3, seriesKey)
            .setParameter(4, java.sql.Timestamp.from(Instant.now())).executeUpdate()
        return em.find(AlertRuleState::class.java, id, LockModeType.PESSIMISTIC_WRITE)
            ?: throw IllegalStateException("Alert rule state for $pipelineId/$nodeId/$seriesKey could not be created")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A node input is one record (map) or a micro-batch (list of maps). */
    private fun records(input: Any?): List<Map<String, Any?>> = when (input) {
        is Map<*, *> -> listOf(input.entries.associate { it.key.toString() to it.value })
        is List<*> -> input.filterIsInstance<Map<*, *>>().map { m -> m.entries.associate { it.key.toString() to it.value } }
        else -> emptyList()
    }

    /** Resolves a dotted field path such as `sensor.temperature`. */
    private fun resolve(record: Map<String, Any?>, path: String): Any? =
        path.split('.').fold(record as Any?) { current, part -> (current as? Map<*, *>)?.get(part) }

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun timestamp(record: Map<String, Any?>, rule: AlertRuleConfig, now: Instant): Instant {
        val raw = rule.timestampField?.let { resolve(record, it) } ?: return now
        number(raw)?.let { n -> return if (n < 1e12) Instant.ofEpochMilli((n * 1000).toLong()) else Instant.ofEpochMilli(n.toLong()) }
        val text = raw.toString()
        return runCatching { Instant.parse(text) }.recoverCatching { OffsetDateTime.parse(text).toInstant() }.getOrDefault(now)
    }

    private fun compare(actual: Any, operator: String, expected: Any?): Boolean {
        val a = number(actual)
        val e = number(expected)
        if (a != null && e != null) {
            return when (operator) {
                ">" -> a > e
                ">=" -> a >= e
                "<" -> a < e
                "<=" -> a <= e
                "==" -> a == e
                "!=" -> a != e
                else -> false
            }
        }
        return when (operator) {
            "==" -> actual.toString() == expected?.toString()
            "!=" -> actual.toString() != expected?.toString()
            else -> false
        }
    }

    private fun format(value: Any?): String = when (value) {
        is Double -> if (value == Math.rint(value) && abs(value) < 1e15) value.toLong().toString() else "%.4g".format(value)
        else -> value.toString()
    }

    private fun seriesSuffix(seriesKey: String) = if (seriesKey.isEmpty()) "" else " [$seriesKey]"
}
