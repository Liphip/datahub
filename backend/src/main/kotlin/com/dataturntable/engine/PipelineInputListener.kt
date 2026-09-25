package com.dataturntable.engine

import com.dataturntable.config.RuntimeRoleConfig
import io.smallrye.reactive.messaging.annotations.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.time.Duration

/**
 * True for failures of the infrastructure rather than of the pipeline: lost or refused
 * database connections (SQLSTATE class 08, admin shutdown 57P*, read-only standby 25006,
 * serialization/deadlock 40*), as seen during a PostgreSQL primary failover.
 */
fun isTransientInfrastructureFailure(e: Throwable): Boolean =
    generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(20).any { t ->
        t is SQLTransientException || t is SQLRecoverableException ||
            (t is SQLException && t.sqlState?.let { s ->
                s.startsWith("08") || s.startsWith("57P") || s.startsWith("40") || s == "25006"
            } == true)
    }

/**
 * Consumes `dataturntable.pipelines.input`. The offset of a record is committed only after
 * this method returns (at-least-once): when an executor dies, its partitions move to another
 * replica, which receives the uncommitted records again ([PipelineLogWriter.claimRun] skips
 * runs that already finished).
 *
 * Infrastructure failures are retried with backoff for up to `max-infra-retry`, holding the
 * partition, so a database failover delays processing instead of failing runs. Only after
 * that the record goes to the dead-letter topic (`failure-strategy: dead-letter-queue`),
 * from where it can be replayed; it is never dropped.
 */
@ApplicationScoped
class PipelineInputListener {

    private val log = Logger.getLogger(PipelineInputListener::class.java)

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @ConfigProperty(name = "dataturntable.pipeline.max-infra-retry", defaultValue = "10m")
    lateinit var maxInfraRetry: Duration

    @Incoming("pipeline-input")
    @Blocking
    fun onRecord(message: String) {
        if (!runtimeRoleConfig.ownsPipelineRuntime()) {
            throw IllegalStateException("pipeline-input consumed by role ${runtimeRoleConfig.role}")
        }
        val deadline = System.nanoTime() + maxInfraRetry.toNanos()
        var backoffMs = 500L
        while (true) {
            try {
                pipelineConsumer.consume(message)
                return
            } catch (e: Exception) {
                if (System.nanoTime() + backoffMs * 1_000_000 > deadline) {
                    log.errorf(e, "Giving up on pipeline record after %s, sending it to the dead-letter topic", maxInfraRetry)
                    throw e
                }
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                log.warnf("Pipeline record not processed (%s; cause: %s), retrying in %d ms", e.message, root.message, backoffMs)
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000)
            }
        }
    }
}
