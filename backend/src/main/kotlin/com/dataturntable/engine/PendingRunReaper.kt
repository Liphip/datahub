package com.dataturntable.engine

import com.dataturntable.config.RuntimeRoleConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant

/**
 * A run stays PENDING forever when the instance that created it stopped before Kafka
 * acknowledged its record; the sender got no 202 and resent the data as a new run.
 * Such runs are marked FAILED with [REAPED_MESSAGE]. Should the record still arrive
 * (a Kafka backlog longer than `max-pending-age`), [PipelineLogWriter.claimRun] accepts
 * reaped runs, so acknowledged data is never skipped.
 */
@ApplicationScoped
class PendingRunReaper {

    private val log = Logger.getLogger(PendingRunReaper::class.java)

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.pipeline.max-pending-age", defaultValue = "30m")
    lateinit var maxPendingAge: Duration

    @Scheduled(every = "5m", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledReap() {
        if (!runtimeRoleConfig.ownsPipelineRuntime()) return
        try {
            reap(Instant.now()).takeIf { it > 0 }?.let { log.infof("Marked %d never-queued runs as failed", it) }
        } catch (e: Exception) {
            log.warnf("Pending run reaper failed: %s", e.message)
        }
    }

    @Transactional
    fun reap(now: Instant): Int =
        em.createQuery(
            "UPDATE PipelineRun r SET r.status = :failed, r.completedAt = :now, r.errorMessage = :msg " +
                "WHERE r.status = :pending AND r.startedAt < :cutoff"
        )
            .setParameter("failed", com.dataturntable.pipeline.PipelineRunStatus.FAILED)
            .setParameter("pending", com.dataturntable.pipeline.PipelineRunStatus.PENDING)
            .setParameter("now", now)
            .setParameter("msg", REAPED_MESSAGE)
            .setParameter("cutoff", now.minus(maxPendingAge))
            .executeUpdate()

    companion object {
        const val REAPED_MESSAGE = "Never queued: the instance stopped before Kafka acknowledged the record (the sender was asked to resend)"
    }
}
