package com.dataturntable.service

import com.dataturntable.config.RuntimeRoleConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant

/**
 * FR-031: every listed data class needs an explicit configurable retention rule or explicit
 * indefinite retention. Each `*-retention-days` property defaults to `-1` (indefinite — no
 * cleanup) so nothing is deleted unless a deployment opts in explicitly. Deleting old
 * `pipeline_runs` rows cascades to `pipeline_run_node_logs` via its existing
 * `ON DELETE CASCADE` FK, covering "run metadata" and "node logs" with one rule. Execution
 * output/logs and intermediate results are not separately persisted outside `pipeline_runs`/
 * `pipeline_run_node_logs` and the ephemeral `Execution` row, so no separate category exists for
 * them; `readings` (time-series) and `audit_events`/`alert_history` are each independently
 * configurable since they have materially different useful lifetimes (audit trails typically need
 * to outlive operational data).
 */
@ApplicationScoped
class RetentionCleanupService {
    private val LOG = Logger.getLogger(RetentionCleanupService::class.java)

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @ConfigProperty(name = "dataturntable.retention.pipeline-runs-days", defaultValue = "-1")
    var pipelineRunRetentionDays: Int = -1

    @ConfigProperty(name = "dataturntable.retention.alert-history-days", defaultValue = "-1")
    var alertHistoryRetentionDays: Int = -1

    @ConfigProperty(name = "dataturntable.retention.audit-events-days", defaultValue = "-1")
    var auditEventRetentionDays: Int = -1

    @ConfigProperty(name = "dataturntable.retention.readings-days", defaultValue = "-1")
    var readingsRetentionDays: Int = -1

    data class CleanupResult(val category: String, val retentionDays: Int, val deleted: Int)

    /**
     * Runs daily on a single owning role so a multi-replica deployment doesn't race duplicate
     * cleanup work — matches the role-gating convention used for pipeline/ingest background work
     * in RuntimeRoleConfig. Cleanup is a control-plane concern, not tied to executing pipelines or
     * ingest, so it's gated on isControlApi() rather than ownsPipelineRuntime()/ownsIngestRuntime().
     */
    @Scheduled(every = "{dataturntable.retention.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun runScheduledCleanup() {
        if (!runtimeRoleConfig.isControlApi()) return
        val results = runCleanup()
        results.filter { it.deleted > 0 }.forEach {
            LOG.infof("Retention cleanup: removed %d %s row(s) older than %d day(s)", it.deleted, it.category, it.retentionDays)
        }
    }

    /** Runs every configured category's cleanup once; each category independently no-ops if its retention is indefinite (< 0). */
    fun runCleanup(): List<CleanupResult> = listOf(
        cleanupPipelineRuns(),
        cleanupAlertHistory(),
        cleanupAuditEvents(),
        cleanupReadings()
    )

    @Transactional
    fun cleanupPipelineRuns(): CleanupResult {
        if (pipelineRunRetentionDays < 0) return CleanupResult("pipeline_runs", pipelineRunRetentionDays, 0)
        val cutoff = Instant.now().minusSeconds(pipelineRunRetentionDays.toLong() * 86_400)
        // Only finished runs. A run row is also the record that makes resends idempotent: once it
        // is deleted, a record resent with the same idempotency key would run again.
        val deleted = em.createNativeQuery(
            "DELETE FROM pipeline_runs WHERE started_at < :cutoff AND status IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMED_OUT')"
        ).setParameter("cutoff", cutoff).executeUpdate()
        return CleanupResult("pipeline_runs", pipelineRunRetentionDays, deleted)
    }

    @Transactional
    fun cleanupAlertHistory(): CleanupResult {
        if (alertHistoryRetentionDays < 0) return CleanupResult("alert_history", alertHistoryRetentionDays, 0)
        val cutoff = Instant.now().minusSeconds(alertHistoryRetentionDays.toLong() * 86_400)
        // Alerts still waiting in the delivery outbox are never removed.
        val deleted = em.createNativeQuery(
            "DELETE FROM alert_history WHERE created_at < :cutoff AND status IN ('SENT', 'SUPPRESSED', 'FAILED')"
        ).setParameter("cutoff", cutoff).executeUpdate()
        return CleanupResult("alert_history", alertHistoryRetentionDays, deleted)
    }

    @Transactional
    fun cleanupAuditEvents(): CleanupResult {
        if (auditEventRetentionDays < 0) return CleanupResult("audit_events", auditEventRetentionDays, 0)
        val cutoff = Instant.now().minusSeconds(auditEventRetentionDays.toLong() * 86_400)
        val deleted = em.createNativeQuery(
            "DELETE FROM audit_events WHERE occurred_at < :cutoff"
        ).setParameter("cutoff", cutoff).executeUpdate()
        return CleanupResult("audit_events", auditEventRetentionDays, deleted)
    }

    @Transactional
    fun cleanupReadings(): CleanupResult {
        if (readingsRetentionDays < 0) return CleanupResult("readings", readingsRetentionDays, 0)
        val cutoff = Instant.now().minusSeconds(readingsRetentionDays.toLong() * 86_400)
        val deleted = em.createNativeQuery(
            "DELETE FROM readings WHERE time < :cutoff"
        ).setParameter("cutoff", cutoff).executeUpdate()
        return CleanupResult("readings", readingsRetentionDays, deleted)
    }
}
