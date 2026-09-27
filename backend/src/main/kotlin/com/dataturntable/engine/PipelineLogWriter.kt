package com.dataturntable.engine

import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunNodeLog
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
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

    /** Kafka coordinates of the record being processed; null when not known (tests, direct calls). */
    data class RecordSource(val partition: Int, val offset: Long)

    /**
     * Claims a run for execution, creating its row when the record comes from machine
     * ingest (those runs are only created here, so ingest does not depend on the database).
     *
     * Returns false — the record must be skipped — when the run already finished, was
     * cancelled, or is RUNNING for a different Kafka record: a second copy of the same run
     * (client resend with the same idempotency key). A RUNNING run claimed by this very
     * record is the redelivery after its executor died, so it is claimed again and its
     * partial node logs are discarded.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun claimRun(
        runId: UUID,
        pipelineId: UUID? = null,
        triggerType: TriggerType = TriggerType.DATA,
        queuedAt: Instant? = null,
        retryOfRunId: UUID? = null,
        source: RecordSource? = null,
    ): Boolean {
        if (pipelineId != null) {
            em.createNativeQuery(
                "INSERT INTO pipeline_runs (id, pipeline_id, status, trigger_type, started_at, queued_at, retry_of_run_id) " +
                    "VALUES (?1, ?2, 'PENDING', ?3, ?4, ?5, ?6) ON CONFLICT DO NOTHING"
            ).setParameter(1, runId).setParameter(2, pipelineId).setParameter(3, triggerType.name)
                .setParameter(4, java.sql.Timestamp.from(Instant.now()))
                .setParameter(5, queuedAt?.let { java.sql.Timestamp.from(it) })
                .setParameter(6, retryOfRunId)
                .executeUpdate()
        }
        val run = em.find(PipelineRun::class.java, runId, LockModeType.PESSIMISTIC_WRITE) ?: return false
        val reaped = run.status == PipelineRunStatus.FAILED && run.errorMessage == PendingRunReaper.REAPED_MESSAGE
        when {
            run.status == PipelineRunStatus.PENDING || reaped -> Unit
            run.status == PipelineRunStatus.RUNNING -> {
                val claimedByOther = source != null && run.recordPartition != null &&
                    (run.recordPartition != source.partition || run.recordOffset != source.offset)
                if (claimedByOther) return false
                em.createQuery("DELETE FROM PipelineRunNodeLog l WHERE l.run.id = :id").setParameter("id", runId).executeUpdate()
            }
            else -> return false
        }
        run.errorMessage = null
        run.completedAt = null
        run.status = PipelineRunStatus.RUNNING
        run.startedAt = Instant.now()
        if (run.queuedAt == null) run.queuedAt = queuedAt
        source?.let { run.recordPartition = it.partition; run.recordOffset = it.offset }
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
