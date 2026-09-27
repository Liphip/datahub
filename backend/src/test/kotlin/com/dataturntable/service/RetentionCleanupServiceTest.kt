package com.dataturntable.service

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * FR-031: run metadata, alert history, audit events, and time-series data each need an explicit
 * configurable retention rule (or explicit indefinite retention). Verifies both halves of that
 * contract: with retention configured, rows older than the cutoff are removed and newer rows
 * survive; with the default (-1, indefinite), nothing is ever deleted.
 */
class ConfiguredRetentionProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "test"
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "dataturntable.retention.pipeline-runs-days" to "30",
        "dataturntable.retention.alert-history-days" to "30",
        "dataturntable.retention.audit-events-days" to "30",
        "dataturntable.retention.readings-days" to "30"
    )
}

@QuarkusTest
@TestProfile(ConfiguredRetentionProfile::class)
class RetentionCleanupServiceTest {

    @Inject
    lateinit var retentionCleanupService: RetentionCleanupService

    @PersistenceContext
    lateinit var em: EntityManager

    @Test
    fun `configured retention deletes rows older than the cutoff and keeps recent rows`() {
        val oldTimestamp = Instant.now().minusSeconds(60L * 86_400) // 60 days old
        val recentTimestamp = Instant.now().minusSeconds(1L * 86_400) // 1 day old

        val oldRunId = UUID.randomUUID()
        val recentRunId = UUID.randomUUID()
        val oldAlertId = UUID.randomUUID()
        val recentAlertId = UUID.randomUUID()
        val oldAuditId = UUID.randomUUID()
        val recentAuditId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "INSERT INTO pipeline_runs (id, status, started_at) VALUES (:id, 'SUCCESS', :startedAt)"
            ).setParameter("id", oldRunId).setParameter("startedAt", oldTimestamp).executeUpdate()
            em.createNativeQuery(
                "INSERT INTO pipeline_runs (id, status, started_at) VALUES (:id, 'SUCCESS', :startedAt)"
            ).setParameter("id", recentRunId).setParameter("startedAt", recentTimestamp).executeUpdate()

            em.createNativeQuery(
                "INSERT INTO alert_history (id, severity, title, sent_at, created_at, status, attempts) VALUES (:id, 'WARNING', 'Old alert', :sentAt, :sentAt, 'SENT', 1)"
            ).setParameter("id", oldAlertId).setParameter("sentAt", oldTimestamp).executeUpdate()
            em.createNativeQuery(
                "INSERT INTO alert_history (id, severity, title, sent_at, created_at, status, attempts) VALUES (:id, 'WARNING', 'Recent alert', :sentAt, :sentAt, 'SENT', 1)"
            ).setParameter("id", recentAlertId).setParameter("sentAt", recentTimestamp).executeUpdate()

            em.createNativeQuery(
                "INSERT INTO audit_events (id, occurred_at, action, resource_type, outcome) VALUES (:id, :occurredAt, 'test.action', 'test', 'succeeded')"
            ).setParameter("id", oldAuditId).setParameter("occurredAt", oldTimestamp).executeUpdate()
            em.createNativeQuery(
                "INSERT INTO audit_events (id, occurred_at, action, resource_type, outcome) VALUES (:id, :occurredAt, 'test.action', 'test', 'succeeded')"
            ).setParameter("id", recentAuditId).setParameter("occurredAt", recentTimestamp).executeUpdate()

            // `readings` has no JPA entity (deliberately — it has no primary key in production,
            // which broke Hibernate's Postgres schema-validate mode when one was tried), so it
            // must be created directly here rather than relying on Hibernate drop-and-create.
            em.createNativeQuery(
                "CREATE TABLE IF NOT EXISTS readings (\"time\" TIMESTAMP WITH TIME ZONE NOT NULL, tenant_id UUID NOT NULL, pipeline_id UUID, metric VARCHAR(255) NOT NULL, \"value\" DOUBLE PRECISION, tags CLOB, raw CLOB)"
            ).executeUpdate()
            em.createNativeQuery(
                "INSERT INTO readings (\"time\", tenant_id, metric, \"value\") VALUES (:time, :tenantId, 'old-metric', 1.0)"
            ).setParameter("time", oldTimestamp).setParameter("tenantId", tenantId).executeUpdate()
            em.createNativeQuery(
                "INSERT INTO readings (\"time\", tenant_id, metric, \"value\") VALUES (:time, :tenantId, 'recent-metric', 2.0)"
            ).setParameter("time", recentTimestamp).setParameter("tenantId", tenantId).executeUpdate()
        }

        val results = retentionCleanupService.runCleanup()
        assertTrue(results.any { it.category == "pipeline_runs" && it.deleted >= 1 })
        assertTrue(results.any { it.category == "alert_history" && it.deleted >= 1 })
        assertTrue(results.any { it.category == "audit_events" && it.deleted >= 1 })
        assertTrue(results.any { it.category == "readings" && it.deleted >= 1 })

        QuarkusTransaction.requiringNew().run {
            assertEquals(0, countById("pipeline_runs", oldRunId))
            assertEquals(1, countById("pipeline_runs", recentRunId))
            assertEquals(0, countById("alert_history", oldAlertId))
            assertEquals(1, countById("alert_history", recentAlertId))
            assertEquals(0, countById("audit_events", oldAuditId))
            assertEquals(1, countById("audit_events", recentAuditId))
            assertEquals(0, countByMetric("old-metric"))
            assertEquals(1, countByMetric("recent-metric"))
        }
    }

    private fun countByMetric(metric: String): Int =
        (em.createNativeQuery("SELECT COUNT(*) FROM readings WHERE metric = :metric").setParameter("metric", metric).singleResult as Number).toInt()

    @Test
    fun `indefinite retention (the default) never deletes anything`() {
        val service = RetentionCleanupService().apply {
            this.em = this@RetentionCleanupServiceTest.em
            this.pipelineRunRetentionDays = -1
            this.alertHistoryRetentionDays = -1
            this.auditEventRetentionDays = -1
            this.readingsRetentionDays = -1
        }
        val results = service.runCleanup()
        results.forEach { assertEquals(0, it.deleted, "indefinite retention (-1) must never delete: ${it.category}") }
    }

    private fun countById(table: String, id: UUID): Int =
        (em.createNativeQuery("SELECT COUNT(*) FROM $table WHERE id = :id").setParameter("id", id).singleResult as Number).toInt()
}
