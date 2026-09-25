package com.dataturntable.engine

import com.dataturntable.pipeline.Pipeline
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
 * [enqueue] creates the run in its own committed transaction (so an executor on another
 * node always finds it) and returns only after the Kafka brokers acknowledged the message
 * (`acks=all`, idempotent producer). Callers therefore acknowledge their source — HTTP 202,
 * MQTT PUBACK, a poll cursor — only for data that is durably stored; on failure they get
 * [PipelineQueueUnavailableException] and the source keeps or re-sends the data.
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
     * Creates a PENDING run for [pipelineId] and publishes it with [data] as the run input.
     * [partitionKey] (e.g. a device id) spreads one pipeline's load over partitions; records
     * with the same key keep their order. Returns the run id.
     */
    fun enqueue(
        pipelineId: UUID,
        triggerType: TriggerType,
        data: Map<String, Any?>,
        retryOfRunId: UUID? = null,
        partitionKey: String? = null,
    ): UUID {
        val runId = try {
            createRun(pipelineId, triggerType, retryOfRunId)
        } catch (e: Exception) {
            if (!isTransientInfrastructureFailure(e)) throw e
            throw PipelineQueueUnavailableException("Database unavailable, retry later", e)
        }
        val input = data + mapOf("_pipelineId" to pipelineId.toString(), "_runId" to runId.toString())
        val message = objectMapper.writeValueAsString(
            PipelineTriggerMessage(runId.toString(), pipelineId.toString(), triggerType.name, input)
        )
        val key = partitionKey?.takeIf { it.isNotBlank() }?.let { "$pipelineId:$it" } ?: pipelineId.toString()
        try {
            emitter.sendMessage(
                Message.of(message).addMetadata(OutgoingKafkaRecordMetadata.builder<String>().withKey(key).build())
            ).await().atMost(sendTimeout)
        } catch (e: Exception) {
            log.errorf("Pipeline %s: run %s could not be queued: %s", pipelineId, runId, e.message)
            // Best effort: with the database also down the run stays PENDING, which the
            // client's resend makes irrelevant.
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
            throw PipelineQueueUnavailableException("Pipeline queue unavailable, retry later", e)
        }
        return runId
    }

    private fun createRun(pipelineId: UUID, triggerType: TriggerType, retryOfRunId: UUID?): UUID =
        QuarkusTransaction.requiringNew().call {
            val run = PipelineRun().apply {
                pipeline = em.getReference(Pipeline::class.java, pipelineId)
                this.triggerType = triggerType
                this.retryOfRunId = retryOfRunId
            }
            em.persist(run)
            em.flush()
            run.id!!
        }
}
