package com.dataturntable.alert

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.events.ConsumerDeadLetter
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.tenant.Tenant
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.Optional

/**
 * Watches the platform's own health and raises platform alerts, for the four conditions named in
 * ALT-13: repeated run failures, growing processing lag, dead-letter records, and alerts that
 * could not be delivered.
 *
 * These are not pipeline alerts: nothing triggered them and no ALERT node raised them, so a tenant
 * cannot configure them away. A failing pipeline and an undelivered alert are exactly the two
 * things an operator would otherwise only notice by the absence of noise.
 *
 * Findings about a tenant's pipelines go to that tenant's default channel, the same "first active
 * channel" an ALERT node without an explicit channel gets, and to the system administrators
 * configured in `dataturntable.health.admin-emails`. Findings with no tenant (dead letters, and
 * undelivered alerts) go to the administrators only: telling a tenant that their alerts are not
 * arriving through the very channel that is failing to deliver them would not help.
 *
 * Runs on the control API only, alongside retention cleanup, so a multi-replica deployment reports
 * each problem once. Every notification is recorded with a `deliveryKey` of problem and window, so
 * a condition that persists is reported once per `dedupeWindowSeconds` rather than on every scan.
 */
@ApplicationScoped
class PlatformHealthMonitor {

    private val log = Logger.getLogger(PlatformHealthMonitor::class.java)

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @Inject
    lateinit var mailer: Mailer

    @PersistenceContext
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.health.enabled", defaultValue = "true")
    var enabled: Boolean = true

    /** Failed runs of one pipeline within [failureWindowSeconds] before it counts as "repeated". */
    @ConfigProperty(name = "dataturntable.health.failure-threshold", defaultValue = "3")
    var failureThreshold: Int = 3

    @ConfigProperty(name = "dataturntable.health.failure-window-seconds", defaultValue = "900")
    var failureWindowSeconds: Long = 900

    /** Age of the oldest unfinished run before the backlog counts as lagging. */
    @ConfigProperty(name = "dataturntable.health.lag-threshold-seconds", defaultValue = "300")
    var lagThresholdSeconds: Long = 300

    /** How long one problem stays reported before it may be raised again. */
    @ConfigProperty(name = "dataturntable.health.dedupe-window-seconds", defaultValue = "3600")
    var dedupeWindowSeconds: Long = 3600

    /** Optional: with no addresses configured, tenants are notified and nothing else is. */
    @ConfigProperty(name = "dataturntable.health.admin-emails")
    var adminEmails: Optional<String> = Optional.empty()

    /**
     * One problem worth telling somebody about.
     *
     * A value, not an entity: it outlives the transaction that found it, and is raised in another
     * one, so it carries the tenant's id and name rather than a detached `Tenant` proxy.
     */
    data class Finding(
        val check: String,
        val key: String,
        val severity: String,
        val title: String,
        val message: String,
        /** Null for a platform-wide problem that belongs to no tenant. */
        val tenantId: UUID?,
        val tenantName: String?,
        val payload: Map<String, Any?>,
    )

    @Scheduled(
        every = "\${dataturntable.health.check-interval:60s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun scheduledCheck() {
        if (!enabled || !runtimeRoleConfig.backgroundEnabled() || !runtimeRoleConfig.isControlApi()) return
        try {
            QuarkusTransaction.requiringNew().call { raise(findings()) }
        } catch (e: Exception) {
            // A health check that throws must not take its own scheduler down with it.
            log.warnf("Platform health check failed: %s", e.message)
        }
    }

    /**
     * Collects the current findings without delivering anything. This is the part worth testing
     * directly: the thresholds and the wording of a message, not the sending.
     */
    @Transactional
    fun findings(now: Instant = Instant.now()): List<Finding> =
        repeatedRunFailures(now) + processingLag(now) + deadLetters(now) + undeliveredAlerts(now)

    /**
     * Raises at most one tenant alert and one administrator email per problem per dedupe window.
     *
     * Not `@Transactional` on purpose. Each finding is recorded in its own transaction, and the
     * alert is delivered only once that transaction has committed: [AlertDispatcher.deliver] claims
     * the row in a nested transaction, which cannot see a row the enclosing one still holds.
     */
    fun raise(findings: List<Finding>, now: Instant = Instant.now()): Int {
        val bucket = now.epochSecond / maxOf(1, dedupeWindowSeconds)
        var raised = 0
        for (finding in findings) {
            val dedupKey = "${PREFIX}${finding.check}:${finding.key}"
            val deliveryKey = "${dedupKey}:$bucket"
            val (tenantAlertId, mailedAdministrators) = QuarkusTransaction.requiringNew().call {
                val tenant = finding.tenantId?.let { em.find(Tenant::class.java, it) }
                // Pre-checked rather than relying on the dispatcher's own dedupe, which returns the
                // id of the row it already has: that would count as raised again and resend.
                val id = if (tenant != null && !alreadyRecorded(deliveryKey)) {
                    alertDispatcher.enqueuePlatformAlert(
                        tenant = tenant,
                        channel = alertDispatcher.defaultChannel(tenant),
                        title = finding.title,
                        message = finding.message,
                        severity = finding.severity,
                        payload = finding.payload + ("check" to finding.check),
                        dedupKey = dedupKey,
                        deliveryKey = deliveryKey,
                    )
                } else {
                    null
                }
                id to notifyAdministrators(finding, "${deliveryKey}:admin")
            }
            // The row is committed now, so the claim in deliver() can see it.
            tenantAlertId?.let { alertDispatcher.deliver(it) }
            if (tenantAlertId != null) raised++
            if (mailedAdministrators) raised++
        }
        return raised
    }

    /**
     * Mails the administrators, and records the attempt as an alert so the window deduplicates it.
     *
     * The row has no channel and is never queued for delivery — it exists as the audit record of a
     * side effect that happened outside the outbox. Its dedup key is prefixed [ADMIN_PREFIX] so that
     * a failed attempt to reach the administrators is not itself reported as an undelivered alert,
     * which would keep the mailbox busy for as long as the mail server stays broken.
     *
     * Returns true when the administrators were told, false when the attempt was already made in
     * this window.
     */
    private fun notifyAdministrators(finding: Finding, deliveryKey: String): Boolean {
        if (alreadyRecorded(deliveryKey)) return false
        val recipients = adminEmails.orElse("").split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        var delivered = false
        var error: String? = null
        if (recipients.isEmpty()) {
            // Nothing to send to. The tenant alert is still raised, so this is not lost information.
            if (finding.tenantId == null) {
                log.warnf("Platform health: %s (no admin addresses configured)", finding.title)
            }
        } else {
            val subject = "[${finding.severity}] ${finding.title}"
            val body = buildString {
                appendLine(finding.message)
                appendLine()
                appendLine("check: ${finding.check}")
                appendLine("tenant: ${finding.tenantName ?: "(platform)"}")
                finding.payload.forEach { (k, v) -> if (v != null) appendLine("$k: $v") }
            }
            runCatching {
                mailer.send(*recipients.map { Mail.withText(it, subject, body) }.toTypedArray())
                delivered = true
            }.onFailure {
                error = it.message
                log.warnf("Could not mail administrators about '%s': %s", finding.title, it.message)
            }
        }
        em.persist(
            AlertHistory().apply {
                this.tenant = finding.tenantId?.let { em.find(Tenant::class.java, it) }
                this.channel = null
                this.pipeline = null
                this.dedupKey = "$ADMIN_PREFIX${finding.check}:${finding.key}"
                this.deliveryKey = deliveryKey
                this.severity = AlertSeverity.valueOf(finding.severity)
                this.title = finding.title
                this.message = finding.message
                this.createdAt = Instant.now()
                // No channel, so the outbox never touches this row: it is already the final state.
                this.status = if (delivered) AlertStatus.SENT else AlertStatus.FAILED
                this.sentAt = if (delivered) Instant.now() else null
                this.lastError = error ?: if (delivered) null else "No administrator addresses configured"
            },
        )
        em.flush()
        return delivered
    }

    private fun alreadyRecorded(deliveryKey: String): Boolean =
        em.createQuery("SELECT a.id FROM AlertHistory a WHERE a.deliveryKey = :k", UUID::class.java)
            .setParameter("k", deliveryKey)
            .resultList
            .isNotEmpty()

    /** A pipeline that keeps failing is a different problem from one that failed once. */
    private fun repeatedRunFailures(now: Instant): List<Finding> {
        val since = now.minusSeconds(failureWindowSeconds)
        val rows: List<Array<Any>> = em.createQuery(
            "SELECT r.pipeline, COUNT(r) FROM PipelineRun r " +
                "WHERE r.status = :status AND r.startedAt > :since AND r.pipeline.isActive = true " +
                "GROUP BY r.pipeline HAVING COUNT(r) >= :threshold",
            Array<Any>::class.java,
        )
            .setParameter("status", PipelineRunStatus.FAILED)
            .setParameter("since", since)
            .setParameter("threshold", failureThreshold.toLong())
            .resultList
        return rows.mapNotNull { row ->
            val pipeline = row[0] as? Pipeline ?: return@mapNotNull null
            val count = (row[1] as Number).toLong()
            Finding(
                check = CHECK_RUN_FAILURES,
                key = pipeline.id.toString(),
                severity = "ERROR",
                title = "Pipeline '${pipeline.name}' failed $count runs within ${failureWindowSeconds}s",
                message = "Active pipeline '${pipeline.name}' (id ${pipeline.id}) failed $count runs in the last " +
                    "$failureWindowSeconds seconds. It is still active, so it will keep failing on every trigger " +
                    "until the cause is found. The latest error was: ${latestError(pipeline)}",
                tenantId = pipeline.tenant?.id,
                tenantName = pipeline.tenant?.name,
                payload = mapOf(
                    "pipelineId" to pipeline.id.toString(),
                    "pipelineName" to pipeline.name,
                    "failedRuns" to count,
                    "windowSeconds" to failureWindowSeconds,
                ),
            )
        }
    }

    private fun latestError(pipeline: Pipeline): String =
        em.createQuery(
            "SELECT r.errorMessage FROM PipelineRun r WHERE r.pipeline = :p AND r.status = :status " +
                "ORDER BY r.startedAt DESC",
            String::class.java,
        )
            .setParameter("p", pipeline)
            .setParameter("status", PipelineRunStatus.FAILED)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
            ?.take(200)
            ?: "no error recorded"

    /** Unfinished runs older than the threshold: triggers are queueing up instead of being processed. */
    private fun processingLag(now: Instant): List<Finding> {
        val rows: List<Array<Any>> = em.createQuery(
            "SELECT r.pipeline, MIN(r.startedAt), COUNT(r) FROM PipelineRun r " +
                "WHERE r.status IN :statuses AND r.pipeline.isActive = true GROUP BY r.pipeline",
            Array<Any>::class.java,
        )
            .setParameter("statuses", listOf(PipelineRunStatus.PENDING, PipelineRunStatus.QUEUED, PipelineRunStatus.RUNNING))
            .resultList
        return rows.mapNotNull { row ->
            val pipeline = row[0] as? Pipeline ?: return@mapNotNull null
            val oldest = row[1] as? Instant ?: return@mapNotNull null
            val waiting = (row[2] as Number).toLong()
            val age = Duration.between(oldest, now).seconds
            if (age < lagThresholdSeconds) return@mapNotNull null
            Finding(
                check = CHECK_LAG,
                key = pipeline.id.toString(),
                severity = "WARNING",
                title = "Pipeline '${pipeline.name}' is ${age}s behind",
                message = "Active pipeline '${pipeline.name}' (id ${pipeline.id}) has $waiting unfinished runs. " +
                    "The oldest has been waiting ${age}s, past the ${lagThresholdSeconds}s threshold, so triggers " +
                    "are queueing up rather than being processed.",
                tenantId = pipeline.tenant?.id,
                tenantName = pipeline.tenant?.name,
                payload = mapOf(
                    "pipelineId" to pipeline.id.toString(),
                    "pipelineName" to pipeline.name,
                    "unfinishedRuns" to waiting,
                    "oldestSeconds" to age,
                    "thresholdSeconds" to lagThresholdSeconds,
                ),
            )
        }
    }

    /** Dead letters are consumer-side: records no subscriber could handle. */
    private fun deadLetters(now: Instant): List<Finding> {
        val since = now.minusSeconds(failureWindowSeconds)
        val count = em.createQuery(
            "SELECT COUNT(c) FROM ConsumerDeadLetter c WHERE c.createdAt > :since",
            java.lang.Long::class.java,
        ).setParameter("since", since).singleResult
        if (count == 0L) return emptyList()
        val byChannel = em.createQuery(
            "SELECT c.channel, COUNT(c) FROM ConsumerDeadLetter c WHERE c.createdAt > :since " +
                "GROUP BY c.channel ORDER BY COUNT(c) DESC",
            Array<Any>::class.java,
        ).setParameter("since", since).resultList
            .associate { (it[0] as String) to (it[1] as Number).toLong() }
        val breakdown = byChannel.entries.joinToString(", ") { entry -> "${entry.value} on '${entry.key}'" }
        return listOf(
            Finding(
                check = CHECK_DEAD_LETTERS,
                key = "dead-letters",
                severity = "WARNING",
                title = "$count records on the dead-letter queue",
                message = "$count records could not be handled by a consumer in the last " +
                    "$failureWindowSeconds seconds and are waiting in the dead-letter queue ($breakdown). " +
                    "They are not retried automatically; inspect the payloads and re-publish them.",
                tenantId = null,

                tenantName = null,
                payload = mapOf("deadLetters" to count, "byChannel" to byChannel, "windowSeconds" to failureWindowSeconds),
            ),
        )
    }

    /**
     * An alert that exhausted its retries. Worth reporting to the administrators, because the
     * tenant's channel is the likely cause and a broken channel is silent in both directions.
     *
     * Rows recorded by [notifyAdministrators] are excluded: they are the record of this check
     * failing to reach the administrators, not evidence about a tenant's channel.
     */
    private fun undeliveredAlerts(now: Instant): List<Finding> {
        val since = now.minusSeconds(failureWindowSeconds)
        val rows: List<Array<Any>> = em.createQuery(
            "SELECT a.tenant, COUNT(a) FROM AlertHistory a WHERE a.status = :status AND a.createdAt > :since " +
                "AND (a.dedupKey IS NULL OR a.dedupKey NOT LIKE :adminPrefix) GROUP BY a.tenant",
            Array<Any>::class.java,
        )
            .setParameter("status", AlertStatus.FAILED)
            .setParameter("since", since)
            .setParameter("adminPrefix", "$ADMIN_PREFIX%")
            .resultList
        val total = rows.sumOf { (it[1] as Number).toLong() }
        if (total == 0L) return emptyList()
        val perTenant = rows.joinToString(", ") { row ->
            val tenant = row[0] as? Tenant
            "${(row[1] as Number).toLong()} for tenant '${tenant?.name ?: "(none)"}'"
        }
        return listOf(
            Finding(
                check = CHECK_UNDELIVERED,
                key = "undelivered",
                severity = "ERROR",
                title = "$total alerts could not be delivered",
                message = "$total alerts exhausted their delivery attempts in the last " +
                    "$failureWindowSeconds seconds and were not delivered: $perTenant. Every alert for those " +
                    "tenants is being lost silently, so check the channel configuration and its secrets.",
                tenantId = null,

                tenantName = null,
                payload = mapOf("undeliveredAlerts" to total, "windowSeconds" to failureWindowSeconds),
            ),
        )
    }

    companion object {
        const val CHECK_RUN_FAILURES = "repeated-run-failures"
        const val CHECK_LAG = "processing-lag"
        const val CHECK_DEAD_LETTERS = "dead-letter-records"
        const val CHECK_UNDELIVERED = "undelivered-alerts"

        /** Marks a platform alert, so it is distinguishable from a tenant's ALERT node alerts. */
        const val PREFIX = "platform:"

        /** Marks the audit row of an administrator email. */
        const val ADMIN_PREFIX = "platform-admin:"
    }
}
