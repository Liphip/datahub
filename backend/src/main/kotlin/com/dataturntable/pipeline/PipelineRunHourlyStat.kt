package com.dataturntable.pipeline

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * OPS-07: aggregated run counts per pipeline/hour/status. [RetentionCleanupService] populates
 * this table just before it deletes `pipeline_runs` rows past the configured detail-retention
 * window, so operational history stays inspectable in aggregate once the per-run detail is gone.
 */
@Entity
@Table(
    name = "pipeline_run_hourly_stats",
    uniqueConstraints = [UniqueConstraint(columnNames = ["pipeline_id", "hour_bucket", "status"])],
)
open class PipelineRunHourlyStat : PanacheBase() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID? = null

    @Column(name = "pipeline_id", nullable = false)
    var pipelineId: UUID? = null

    @Column(name = "hour_bucket", nullable = false)
    var hourBucket: Instant? = null

    @Column(nullable = false, length = 20)
    var status: String = ""

    @Column(name = "run_count", nullable = false)
    var runCount: Int = 0

    companion object : PanacheCompanionBase<PipelineRunHourlyStat, UUID> {
        override fun findById(id: UUID): PipelineRunHourlyStat? =
            find("id = ?1", id).firstResult()

        /** Aggregated history for a pipeline, oldest bucket first, optionally from a given hour onward. */
        fun findByPipeline(pipelineId: UUID, since: Instant? = null): List<PipelineRunHourlyStat> =
            if (since != null) {
                list("pipelineId = ?1 and hourBucket >= ?2 order by hourBucket", pipelineId, since)
            } else {
                list("pipelineId = ?1 order by hourBucket", pipelineId)
            }
    }
}
