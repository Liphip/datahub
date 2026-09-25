package com.dataturntable.pipeline

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Defines the enum class `PipelineRunStatus`, the contract implemented by this source unit.
 */
enum class PipelineRunStatus {
    PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
}

/**
 * Defines the enum class `TriggerType`, the contract implemented by this source unit.
 */
enum class TriggerType {
    MANUAL, SCHEDULED, EVENT, DATA
}

@Entity
@Table(name = "pipeline_runs")
/**
 * Defines the class `PipelineRun`, the contract implemented by this source unit.
 */
open class PipelineRun : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id")
    var pipeline: Pipeline? = null
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PipelineRunStatus = PipelineRunStatus.PENDING
    
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type")
    var triggerType: TriggerType = TriggerType.MANUAL
    
    @Column(name = "started_at")
    var startedAt: Instant = Instant.now()
    
    @Column(name = "completed_at")
    var completedAt: Instant? = null
    
    @Column(name = "error_message")
    var errorMessage: String? = null

    @Column(name = "retry_of_run_id")
    var retryOfRunId: UUID? = null
    
    @Column(columnDefinition = "text")
    var metrics: String? = null

    /** Trigger data of a failed run, kept so that a retry replays the same input. */
    @Column(name = "input_payload", columnDefinition = "text")
    var inputPayload: String? = null
    
    companion object : PanacheCompanionBase<PipelineRun, UUID> {
        override fun findById(id: UUID): PipelineRun? =
            find("id = ?1", id).firstResult()

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByPipelineId(pipelineId: UUID): List<PipelineRun> =
            list("pipeline.id = ?1 ORDER BY startedAt DESC", pipelineId)

        /**
         * Retrieves or enumerates the requested state.
         */
        fun findRunningByPipeline(pipelineId: UUID): PipelineRun? =
            find("pipeline.id = ?1 AND status = ?2", pipelineId, PipelineRunStatus.RUNNING).firstResult()
    }
    
    /**
     * Implements `markRunning` for this source unit.
     */
    fun markRunning() {
        status = PipelineRunStatus.RUNNING
        startedAt = Instant.now()
    }
    
    /**
     * Implements `markSuccess` for this source unit.
     */
    fun markSuccess() {
        status = PipelineRunStatus.SUCCESS
        completedAt = Instant.now()
    }
    
    /**
     * Implements `markFailed` for this source unit.
     */
    fun markFailed(error: String?) {
        status = PipelineRunStatus.FAILED
        completedAt = Instant.now()
        errorMessage = error
    }
    
    /**
     * Implements `markCancelled` for this source unit.
     */
    fun markCancelled() {
        status = PipelineRunStatus.CANCELLED
        completedAt = Instant.now()
    }
}
