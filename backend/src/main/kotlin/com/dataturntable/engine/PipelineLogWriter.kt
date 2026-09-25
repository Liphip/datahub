package com.dataturntable.engine

import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunNodeLog
import com.dataturntable.pipeline.PipelineRunStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Handles all pipeline run DB writes in **separate transactions** so that
 * status updates and node logs are immediately visible to the API (and
 * therefore to the polling frontend) while the pipeline is still executing.
 *
 * Every public method runs in REQUIRES_NEW — callers must NOT wrap calls
 * in their own transaction.
 */
@ApplicationScoped
/**
 * Defines the class `PipelineLogWriter`, the contract implemented by this source unit.
 */
class PipelineLogWriter {

    private val LOG = Logger.getLogger(PipelineLogWriter::class.java)

    @PersistenceContext
    lateinit var em: EntityManager

    // ── Run lifecycle ────────────────────────────────────────────────────────

    /**
     * Marks a queued run RUNNING. Returns false when the run is already finished or
     * cancelled, i.e. the Kafka record is a redelivery that must not execute again.
     * A run left RUNNING by an executor that died is claimed again.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun claimRun(runId: UUID): Boolean {
        val run = em.find(PipelineRun::class.java, runId, LockModeType.PESSIMISTIC_WRITE) ?: return false
        val reaped = run.status == PipelineRunStatus.FAILED && run.errorMessage == PendingRunReaper.REAPED_MESSAGE
        if (run.status != PipelineRunStatus.PENDING && run.status != PipelineRunStatus.RUNNING && !reaped) return false
        run.errorMessage = null
        run.completedAt = null
        run.status = PipelineRunStatus.RUNNING
        run.startedAt = Instant.now()
        return true
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    /**
     * Implements `markSuccess` for this source unit.
     */
    fun markSuccess(runId: UUID, metrics: String?) {
        val run = em.find(PipelineRun::class.java, runId) ?: return
        run.status = PipelineRunStatus.SUCCESS
        run.completedAt = Instant.now()
        run.metrics = metrics
        em.merge(run)
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    /**
     * Implements `markFailed` for this source unit.
     */
    fun markFailed(runId: UUID, errorMessage: String?, metrics: String?, input: String? = null) {
        val run = em.find(PipelineRun::class.java, runId) ?: return
        run.status = PipelineRunStatus.FAILED
        run.completedAt = Instant.now()
        run.errorMessage = errorMessage
        run.metrics = metrics
        run.inputPayload = input
        em.merge(run)
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isCancelled(runId: UUID): Boolean {
        val run = em.find(PipelineRun::class.java, runId) ?: return false
        return run.status == PipelineRunStatus.CANCELLED
    }

    // ── Node logs ────────────────────────────────────────────────────────────

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    /**
     * Creates or persists the requested state.
     */
    fun persistNodeLog(
        runId: UUID,
        nodeId: String,
        nodeType: String,
        moduleName: String?,
        success: Boolean,
        stdout: String?,
        stderr: String?,
        errorMessage: String?,
        durationMs: Long?,
    ) {
        val run = em.find(PipelineRun::class.java, runId) ?: run {
            LOG.warnf("Cannot persist node log — run %s not found", runId)
            return
        }
        val log = PipelineRunNodeLog().apply {
            this.run = run
            this.nodeId = nodeId
            this.nodeType = nodeType
            this.moduleName = moduleName
            this.success = success
            this.stdout = stdout
            this.stderr = stderr
            this.errorMessage = errorMessage
            this.durationMs = durationMs
            this.executedAt = Instant.now()
        }
        em.persist(log)
    }
}
