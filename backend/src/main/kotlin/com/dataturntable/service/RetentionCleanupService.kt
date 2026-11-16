package com.dataturntable.service

import com.dataturntable.audit.AuditService
import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.pipeline.PipelineRunStatus
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

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
 *
 * OPS-07: `cleanupPipelineRuns` folds the rows it is about to delete into
 * [com.dataturntable.pipeline.PipelineRunHourlyStat] (counts per pipeline/hour/status) first, so
 * run history stays inspectable in aggregate — throughput and failure-rate trends, not individual
 * runs — once the per-run detail past the retention window is gone.
 */
@ApplicationScoped
class RetentionCleanupService {
    private val LOG = Logger.getLogger(RetentionCleanupService::class.java)

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var audit: AuditService

    @ConfigProperty(name = "dataturntable.retention.pipeline-runs-days", defaultValue = "-1")
    var pipelineRunRetentionDays: Int = -1

    @ConfigProperty(name = "dataturntable.retention.alert-history-days", defaultValue = "-1")
    var alertHistoryRetentionDays: Int = -1

    @ConfigProperty(name = "dataturntable.retention.audit-events-days", defaultValue = "-1")
    var auditEventRetentionDays: Int = -1

    @ConfigProperty(name = "dataturntable.retention.readings-days", defaultValue = "-1")
    var readingsRetentionDays: Int = -1

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    lateinit var dbKind: String

    private val quotaMapper = com.fasterxml.jackson.databind.ObjectMapper()

    data class CleanupResult(val category: String, val retentionDays: Int, val deleted: Int)

    private data class HourlyStatKey(val tenantId: UUID, val pipelineId: UUID, val hourBucket: Instant, val status: String)

    /**
     * Runs daily on a single owning role so a multi-replica deployment doesn't race duplicate
     * cleanup work — matches the role-gating convention used for pipeline/ingest background work
     * in RuntimeRoleConfig. Cleanup is a control-plane concern, not tied to executing pipelines or
     * ingest, so it's gated on isControlApi() rather than ownsPipelineRuntime()/ownsIngestRuntime().
     */
    @Scheduled(every = "{dataturntable.retention.check-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun runScheduledCleanup() {
        // Background work is off in tests (dataturntable.runtime.background-enabled), like the other jobs.
        if (!runtimeRoleConfig.backgroundEnabled() || !runtimeRoleConfig.isControlApi()) return
        val results = runCleanup()
        results.filter { it.deleted > 0 }.forEach {
            LOG.infof("Retention cleanup: removed %d %s row(s) older than %d day(s)", it.deleted, it.category, it.retentionDays)
        }
    }

    /**
     * Runs every configured category's cleanup once; each category independently no-ops if its
     * retention is indefinite (< 0). AUD-01: records one audit event summarizing what was deleted
     * when at least one category actually deleted rows — a no-op run (nothing past any configured
     * cutoff, or every category set to indefinite) writes nothing, matching the existing
     * `results.filter { it.deleted > 0 }` logging convention in [runScheduledCleanup]. Recorded
     * with `actorId = null` (system-initiated, no HTTP caller), the same convention
     * `AuthResource.login` uses for `auth.login.failed`.
     */
    fun runCleanup(): List<CleanupResult> {
        val results = listOf(
            cleanupPipelineRuns(),
            cleanupAlertHistory(),
            cleanupAuditEvents(),
            cleanupReadings()
        )
        val deleted = results.filter { it.deleted > 0 }
        if (deleted.isNotEmpty()) {
            audit.record(
                actorId = null,
                tenantId = null,
                action = "retention.cleanup",
                resourceType = "retention_cleanup",
                resourceId = null,
                metadata = deleted.associate { it.category to it.deleted }
            )
        }
        return results
    }

    /**
     * STR-07: a tenant's own retention, from its quota JSON ([key] in days, `-1` = keep
     * indefinitely), overriding the installation-wide setting for that tenant only. A tenant
     * whose quota cannot be read simply has no override.
     */
    internal fun tenantOverrides(key: String): Map<UUID, Int> =
        em.createQuery("SELECT t.id, t.quota FROM Tenant t WHERE t.quota IS NOT NULL").resultList
            .mapNotNull { row ->
                row as Array<*>
                val days = runCatching { (quotaMapper.readValue(row[1] as String, Map::class.java)[key] as? Number)?.toInt() }.getOrNull()
                if (days == null) null else (row[0] as UUID) to days
            }.toMap()

    private fun cutoff(days: Int): Instant = Instant.now().minusSeconds(days.toLong() * 86_400)

    @Transactional
    fun cleanupPipelineRuns(): CleanupResult {
        val overrides = tenantOverrides("retentionPipelineRunsDays")
        var deleted = 0
        overrides.filterValues { it >= 0 }.forEach { (tenantId, days) -> deleted += purgeRuns(cutoff(days), only = setOf(tenantId)) }
        if (pipelineRunRetentionDays >= 0) deleted += purgeRuns(cutoff(pipelineRunRetentionDays), except = overrides.keys)
        return CleanupResult("pipeline_runs", pipelineRunRetentionDays, deleted)
    }

    /** Deletes finished runs older than [cutoff], of the tenants in [only] or of all but those in [except]. */
    private fun purgeRuns(cutoff: Instant, only: Set<UUID>? = null, except: Set<UUID> = emptySet()): Int {
        // OPS-07: before the detail is gone, fold it into pipeline_run_hourly_stats so run history
        // stays inspectable in aggregate (per pipeline/hour/status) even past the detail window.
        // JPQL rather than native SQL: a native query's UUID columns come back as raw bytes under
        // H2, not java.util.UUID, while JPQL goes through Hibernate's mapped UUID type either way.
        // Runs with no pipeline (should not happen in practice; the column is nullable at the
        // schema level only) are excluded, since there's nothing to key an aggregate row by.
        val finishedStatuses = listOf(PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILED, PipelineRunStatus.CANCELLED, PipelineRunStatus.TIMED_OUT)
        val tenantClause = when {
            only != null -> " AND r.pipeline.tenant.id IN :tenants"
            except.isNotEmpty() -> " AND r.pipeline.tenant.id NOT IN :tenants"
            else -> ""
        }
        val tenants = only ?: except
        @Suppress("UNCHECKED_CAST")
        val rows = em.createQuery(
            """SELECT r.pipeline.tenant.id, r.pipeline.id, r.startedAt, r.status
               FROM PipelineRun r
               WHERE r.startedAt < :cutoff AND r.status IN :statuses AND r.pipeline IS NOT NULL$tenantClause"""
        ).setParameter("cutoff", cutoff).setParameter("statuses", finishedStatuses)
            .also { if (tenantClause.isNotEmpty()) it.setParameter("tenants", tenants) }
            .resultList as List<Array<Any>>

        val counts = mutableMapOf<HourlyStatKey, Int>()
        for (row in rows) {
            val tenantId = row[0] as UUID
            val pipelineId = row[1] as UUID
            val startedAt = row[2] as Instant
            val status = (row[3] as PipelineRunStatus).name
            val key = HourlyStatKey(tenantId, pipelineId, startedAt.truncatedTo(ChronoUnit.HOURS), status)
            counts[key] = (counts[key] ?: 0) + 1
        }
        counts.forEach { (key, count) -> upsertHourlyStat(key, count) }

        // Only finished runs. A run row is also the record that makes resends idempotent: once it
        // is deleted, a record resent with the same idempotency key would run again.
        val base = "DELETE FROM pipeline_runs WHERE started_at < :cutoff AND status IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMED_OUT')"
        val sql = when {
            only != null -> "$base AND pipeline_id IN (SELECT id FROM pipelines WHERE tenant_id IN (:tenants))"
            // Runs without a pipeline belong to no tenant and follow the installation-wide rule.
            except.isNotEmpty() -> "$base AND (pipeline_id IS NULL OR pipeline_id NOT IN (SELECT id FROM pipelines WHERE tenant_id IN (:tenants)))"
            else -> base
        }
        return em.createNativeQuery(sql).setParameter("cutoff", cutoff)
            .also { if (only != null || except.isNotEmpty()) it.setParameter("tenants", tenants) }
            .executeUpdate()
    }

    /**
     * PostgreSQL's `ON CONFLICT` cannot be attempted against H2 (test profile) — see
     * `DataResource.upsertTableRegistryEntry` for why the dialect is checked upfront rather than
     * relying on a caught `SQLGrammarException`, which would still leave a surrounding JTA
     * transaction rollback-only.
     */
    private fun upsertHourlyStat(key: HourlyStatKey, count: Int) {
        if (dbKind == "h2") {
            val existing = em.createNativeQuery(
                "SELECT run_count FROM pipeline_run_hourly_stats WHERE pipeline_id = :pid AND hour_bucket = :hour AND status = :status"
            ).setParameter("pid", key.pipelineId).setParameter("hour", key.hourBucket).setParameter("status", key.status)
                .resultList.firstOrNull()
            if (existing != null) {
                em.createNativeQuery(
                    "UPDATE pipeline_run_hourly_stats SET run_count = run_count + :count WHERE pipeline_id = :pid AND hour_bucket = :hour AND status = :status"
                ).setParameter("count", count).setParameter("pid", key.pipelineId).setParameter("hour", key.hourBucket)
                    .setParameter("status", key.status).executeUpdate()
            } else {
                em.createNativeQuery(
                    "INSERT INTO pipeline_run_hourly_stats (id, tenant_id, pipeline_id, hour_bucket, status, run_count) VALUES (random_uuid(), :tid, :pid, :hour, :status, :count)"
                ).setParameter("tid", key.tenantId).setParameter("pid", key.pipelineId).setParameter("hour", key.hourBucket)
                    .setParameter("status", key.status).setParameter("count", count).executeUpdate()
            }
            return
        }
        em.createNativeQuery(
            """INSERT INTO pipeline_run_hourly_stats (id, tenant_id, pipeline_id, hour_bucket, status, run_count)
               VALUES (gen_random_uuid(), :tid, :pid, :hour, :status, :count)
               ON CONFLICT (pipeline_id, hour_bucket, status) DO UPDATE SET run_count = pipeline_run_hourly_stats.run_count + EXCLUDED.run_count"""
        ).setParameter("tid", key.tenantId).setParameter("pid", key.pipelineId).setParameter("hour", key.hourBucket)
            .setParameter("status", key.status).setParameter("count", count).executeUpdate()
    }

    @Transactional
    fun cleanupAlertHistory(): CleanupResult {
        val overrides = tenantOverrides("retentionAlertHistoryDays")
        // Alerts still waiting in the delivery outbox are never removed.
        val base = "DELETE FROM alert_history WHERE created_at < :cutoff AND status IN ('SENT', 'SUPPRESSED', 'FAILED')"
        var deleted = 0
        overrides.filterValues { it >= 0 }.forEach { (tenantId, days) ->
            deleted += em.createNativeQuery("$base AND tenant_id = :tenant")
                .setParameter("cutoff", cutoff(days)).setParameter("tenant", tenantId).executeUpdate()
        }
        if (alertHistoryRetentionDays >= 0) {
            deleted += if (overrides.isEmpty()) {
                em.createNativeQuery(base).setParameter("cutoff", cutoff(alertHistoryRetentionDays)).executeUpdate()
            } else {
                em.createNativeQuery("$base AND (tenant_id IS NULL OR tenant_id NOT IN (:tenants))")
                    .setParameter("cutoff", cutoff(alertHistoryRetentionDays)).setParameter("tenants", overrides.keys).executeUpdate()
            }
        }
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
