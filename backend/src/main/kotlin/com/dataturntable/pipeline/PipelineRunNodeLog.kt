package com.dataturntable.pipeline

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Stores stdout/stderr and outcome for each node execution within a pipeline run.
 * Persisted after every module container exits so they survive restarts and are
 * visible in the UI without keeping anything in memory.
 */
@Entity
@Table(name = "pipeline_run_node_logs", indexes = [
    Index(name = "idx_prnl_run_id", columnList = "run_id"),
])
open class PipelineRunNodeLog : PanacheBase() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    var run: PipelineRun? = null

    @Column(name = "node_id", nullable = false)
    var nodeId: String = ""

    @Column(name = "node_type", nullable = false)
    var nodeType: String = ""

    @Column(name = "module_name")
    var moduleName: String? = null

    @Column(nullable = false)
    var success: Boolean = false

    /** Trimmed stdout from the Python container (max ~16 KB). */
    @Column(columnDefinition = "text")
    var stdout: String? = null

    /** Trimmed stderr from the Python container (max ~8 KB). */
    @Column(columnDefinition = "text")
    var stderr: String? = null

    /** Platform-level error message (not from the container). */
    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null

    @Column(name = "duration_ms")
    var durationMs: Long? = null

    @Column(name = "executed_at", nullable = false)
    var executedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<PipelineRunNodeLog, UUID> {
        fun findByRunId(runId: UUID): List<PipelineRunNodeLog> =
            list("run.id = ?1 ORDER BY executedAt ASC", runId)
    }
}
