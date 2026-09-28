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
    PENDING, QUEUED, RUNNING, CANCELLING, CANCELLED, SUCCESS, FAILED, TIMED_OUT
}

object PipelineRunStateMachine {
    private val transitions = mapOf(
        PipelineRunStatus.PENDING to setOf(PipelineRunStatus.QUEUED, PipelineRunStatus.CANCELLED),
        PipelineRunStatus.QUEUED to setOf(PipelineRunStatus.RUNNING, PipelineRunStatus.CANCELLING, PipelineRunStatus.CANCELLED, PipelineRunStatus.FAILED),
        PipelineRunStatus.RUNNING to setOf(PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILED, PipelineRunStatus.TIMED_OUT, PipelineRunStatus.CANCELLING),
        PipelineRunStatus.CANCELLING to setOf(PipelineRunStatus.CANCELLED, PipelineRunStatus.FAILED, PipelineRunStatus.TIMED_OUT)
    )
    fun canTransition(from: PipelineRunStatus, to: PipelineRunStatus) = to in (transitions[from] ?: emptySet())
    fun terminal(status: PipelineRunStatus) = status in setOf(PipelineRunStatus.CANCELLED, PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILED, PipelineRunStatus.TIMED_OUT)
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_version_id")
    var pipelineVersion: PipelineVersion? = null
    
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

    /** When the record was handed to Kafka (the run row itself is created on first processing). */
    @Column(name = "queued_at")
    var queuedAt: Instant? = null

    /** Kafka record that claimed the run; a different copy of the same run is a duplicate. */
    @Column(name = "record_partition")
    var recordPartition: Int? = null

    @Column(name = "record_offset")
    var recordOffset: Long? = null
    
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
        if (status == PipelineRunStatus.PENDING) status = PipelineRunStatus.QUEUED
        require(PipelineRunStateMachine.canTransition(status, PipelineRunStatus.RUNNING)) { "Illegal run transition $status -> RUNNING" }
        status = PipelineRunStatus.RUNNING
        startedAt = Instant.now()
    }
    
    /**
     * Implements `markSuccess` for this source unit.
     */
    fun markSuccess() {
        require(PipelineRunStateMachine.canTransition(status, PipelineRunStatus.SUCCESS)) { "Illegal run transition $status -> SUCCESS" }
        status = PipelineRunStatus.SUCCESS
        completedAt = Instant.now()
    }
    
    /**
     * Implements `markFailed` for this source unit.
     */
    fun markFailed(error: String?) {
        // Submission can fail before a consumer claims the run; model that as
        // PENDING -> QUEUED -> FAILED rather than permitting a shortcut.
        if (status == PipelineRunStatus.PENDING) status = PipelineRunStatus.QUEUED
        require(PipelineRunStateMachine.canTransition(status, PipelineRunStatus.FAILED)) { "Illegal run transition $status -> FAILED" }
        status = PipelineRunStatus.FAILED
        completedAt = Instant.now()
        errorMessage = error
    }
    
    /**
     * Implements `markCancelled` for this source unit.
     */
    fun markCancelled() {
        if (status == PipelineRunStatus.RUNNING) status = PipelineRunStatus.CANCELLING
        require(PipelineRunStateMachine.canTransition(status, PipelineRunStatus.CANCELLED)) { "Illegal run transition $status -> CANCELLED" }
        status = PipelineRunStatus.CANCELLED
        completedAt = Instant.now()
    }
}
