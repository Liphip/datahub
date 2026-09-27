package com.dataturntable.engine

import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.narayana.jta.QuarkusTransaction
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Thrown when a run could not be handed to Kafka; the caller must not acknowledge its input. */
class PipelineQueueUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * The only way work reaches the pipeline executors.
 *
 * [enqueue] returns only after the Kafka brokers acknowledged the record (`acks=all`,
 * idempotent producer, replicated topic). Callers therefore acknowledge their source —
 * HTTP 202, MQTT PUBACK — only for data that is durably stored; on failure they get
 * [PipelineQueueUnavailableException] and the source keeps or re-sends the data.
 *
 * Machine ingest does not touch the database: the run row is created by the executor that
 * processes the record ([PipelineLogWriter.claimRun]), so a PostgreSQL failover does not
 * make clients resend. With an idempotency key the run id is derived from it, so a resend
 * of the same data maps to the same run and is processed once.
 */
@ApplicationScoped
class PipelineRunQueue {

    private val log = Logger.getLogger(PipelineRunQueue::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    @Channel("pipeline-commands")
    lateinit var emitter: MutinyEmitter<String>

    @PersistenceContext
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.kafka.send-timeout", defaultValue = "30s")
    lateinit var sendTimeout: Duration

    /**
     * Publishes a run of [pipelineId] with [data] as its input and returns the run id.
     *
     * [idempotencyKey]: the same key for the same pipeline always yields the same run id, and
     * only the first record of a run is executed. [partitionKey] (e.g. a device id) spreads one
     * pipeline's load over partitions while records with the same key keep their order.
     * [persistRun] creates the PENDING row right away (runs started from the UI, which show
     * them immediately); machine ingest leaves that to the executor.
     */
    fun enqueue(
        pipelineId: UUID,
        triggerType: TriggerType,
        data: Map<String, Any?>,
        retryOfRunId: UUID? = null,
        partitionKey: String? = null,
        idempotencyKey: String? = null,
        persistRun: Boolean = false,
    ): UUID {
        val runId = idempotencyKey?.takeIf { it.isNotBlank() }?.let { runIdFor(pipelineId, it) } ?: UUID.randomUUID()
        val queuedAt = Instant.now()
        if (persistRun) {
            try {
                createRun(runId, pipelineId, triggerType, retryOfRunId, queuedAt)
            } catch (e: Exception) {
                if (!isTransientInfrastructureFailure(e)) throw e
                throw PipelineQueueUnavailableException("Database unavailable, retry later", e)
            }
        }
        val input = data + listOfNotNull(
            "_pipelineId" to pipelineId.toString(),
            "_runId" to runId.toString(),
            retryOfRunId?.let { "_retryOfRunId" to it.toString() },
        )
        val message = objectMapper.writeValueAsString(
            PipelineTriggerMessage(runId.toString(), pipelineId.toString(), triggerType.name, input, queuedAt.toString())
        )
        val key = partitionKey?.takeIf { it.isNotBlank() }?.let { "$pipelineId:$it" } ?: pipelineId.toString()
        try {
            emitter.sendMessage(
                Message.of(message).addMetadata(OutgoingKafkaRecordMetadata.builder<String>().withKey(key).build())
            ).await().atMost(sendTimeout)
        } catch (e: Exception) {
            log.errorf("Pipeline %s: run %s could not be queued: %s", pipelineId, runId, e.message)
            if (persistRun) {
                // Best effort: with the database also down the run stays PENDING until the reaper.
                runCatching {
                    QuarkusTransaction.requiringNew().run {
                        em.find(PipelineRun::class.java, runId)?.apply {
                            status = PipelineRunStatus.FAILED
                            completedAt = Instant.now()
                            errorMessage = "Could not queue run: ${e.message}"
                            inputPayload = objectMapper.writeValueAsString(input)
                        }
                    }
                }
            }
            throw PipelineQueueUnavailableException("Pipeline queue unavailable, retry later", e)
        }
        return runId
    }

    private fun createRun(runId: UUID, pipelineId: UUID, triggerType: TriggerType, retryOfRunId: UUID?, queuedAt: Instant) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "INSERT INTO pipeline_runs (id, pipeline_id, status, trigger_type, started_at, queued_at, retry_of_run_id) " +
                    "VALUES (?1, ?2, 'PENDING', ?3, ?4, ?4, ?5) ON CONFLICT DO NOTHING"
            ).setParameter(1, runId).setParameter(2, pipelineId).setParameter(3, triggerType.name)
                .setParameter(4, java.sql.Timestamp.from(queuedAt)).setParameter(5, retryOfRunId)
                .executeUpdate()
        }
    }

    companion object {
        /** Run id of an idempotency key: stable across resends and ingest replicas. */
        fun runIdFor(pipelineId: UUID, idempotencyKey: String): UUID =
            UUID.nameUUIDFromBytes("dataturntable-run|$pipelineId|$idempotencyKey".toByteArray(Charsets.UTF_8))
    }
}
