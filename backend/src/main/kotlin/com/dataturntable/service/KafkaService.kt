package com.dataturntable.service

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.events.EventEnvelope
import com.dataturntable.events.EventEnvelopeValidator
import com.dataturntable.events.EventOutboxService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

@ApplicationScoped
/**
 * Defines the class `KafkaService`, the contract implemented by this source unit.
 */
class KafkaService {
    @PersistenceContext
    lateinit var em: EntityManager
    
    @ConfigProperty(name = "dataturntable.kafka.topics.prefix", defaultValue = "dataturntable")
    lateinit var topicPrefix: String
    
    @Inject lateinit var outbox: EventOutboxService
    
    // EventEnvelope.occurredAt is an Instant, which the default mapper cannot serialize.
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
    private val activeConsumers = ConcurrentHashMap<String, Boolean>()

    /**
     * Evaluates the named capability or state predicate.
     */
    fun isKafkaAvailable(): Boolean = true
    
    /**
     * Creates or persists the requested state.
     */
    fun createPipelineTopics(pipelineId: String, consumerGroup: String) {
        val topics = listOf(
            "$topicPrefix.pipelines.$pipelineId.import",
            "$topicPrefix.pipelines.$pipelineId.processing",
            "$topicPrefix.pipelines.$pipelineId.export",
            "$topicPrefix.pipelines.$pipelineId.alerts"
        )
        
        topics.forEach { topic ->
            // Topics will be created automatically when first used
            // For production, use AdminClient to pre-create with specific config
            println("Would create topic: $topic with consumer group: $consumerGroup")
        }
    }
    
    /**
     * Removes, stops, or releases the requested state.
     */
    fun deletePipelineTopics(pipelineId: String) {
        val topics = listOf(
            "$topicPrefix.pipelines.$pipelineId.import",
            "$topicPrefix.pipelines.$pipelineId.processing",
            "$topicPrefix.pipelines.$pipelineId.export",
            "$topicPrefix.pipelines.$pipelineId.alerts"
        )
        
        topics.forEach { topic ->
            println("Would delete topic: $topic")
        }
    }
    
    /**
     * Implements `triggerPipeline` for this source unit.
     */
    fun triggerPipeline(pipelineId: String, runId: String, triggerType: TriggerType) {
        triggerPipelineWithData(pipelineId, runId, triggerType, null)
    }
    
    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    fun startPipelineConsumers(pipeline: Pipeline) {
        val consumerGroup = pipeline.kafkaConsumerGroup ?: return
        activeConsumers[consumerGroup] = true
        println("Starting consumers for pipeline: ${pipeline.id} with group: $consumerGroup")
    }
    
    /**
     * Removes, stops, or releases the requested state.
     */
    fun stopPipelineConsumers(pipeline: Pipeline) {
        val consumerGroup = pipeline.kafkaConsumerGroup ?: return
        activeConsumers.remove(consumerGroup)
        println("Stopping consumers for pipeline: ${pipeline.id} with group: $consumerGroup")
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isConsumerActive(consumerGroup: String): Boolean {
        return activeConsumers.containsKey(consumerGroup)
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getImportTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.import"
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getProcessingTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.processing"
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getExportTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.export"
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getAlertsTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.alerts"
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getPipelineInputTopic(): String = "$topicPrefix.pipelines.input"
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getPipelineOutputTopic(): String = "$topicPrefix.pipelines.output"
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    fun sendToPipelineSpecificInput(pipelineId: String, data: Any) {
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid pipeline ID for input")
        val pipeline = em.find(Pipeline::class.java, pipelineUuid)
            ?: throw IllegalArgumentException("Pipeline not found for input")
        val tenantId = pipeline.tenant?.id ?: throw IllegalStateException("Pipeline tenant missing")
        val correlationId = (data as? Map<*, *>)?.get("_correlationId")?.toString()
            ?: (data as? Map<*, *>)?.get("correlationId")?.toString()
            ?: UUID.randomUUID().toString()
        val envelope = EventEnvelope(
            type = "pipeline.data.received",
            tenantId = tenantId,
            correlationId = correlationId,
            pipelineId = pipelineUuid,
            payload = data
        )
        EventEnvelopeValidator.requireValid(envelope)
        publishPipelineInput(pipelineId, objectMapper.writeValueAsString(envelope))
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    fun sendToPipelineInput(pipelineId: String, data: Any) {
        sendToPipelineSpecificInput(pipelineId, data)
    }

    /**
     * Trigger a pipeline run with an attached data payload (for event-driven triggers like MQTT or polling).
     * The data is embedded in the PipelineTriggerMessage so PipelineConsumer makes it available
     * to the first IMPORT module as INPUT_DATA.
     */
    fun triggerPipelineWithData(pipelineId: String, runId: String, triggerType: com.dataturntable.pipeline.TriggerType, data: Any?) {
        val pipelineUuid = runCatching { UUID.fromString(pipelineId) }.getOrNull()
        val pipeline = pipelineUuid?.let { em.find(Pipeline::class.java, it) }
            ?: throw IllegalArgumentException("Pipeline not found for trigger")
        val tenantId = pipeline.tenant?.id ?: throw IllegalStateException("Pipeline tenant missing")
        val runUuid = runCatching { UUID.fromString(runId) }.getOrNull()
        val envelope = EventEnvelope(
            type = "pipeline.run.requested",
            tenantId = tenantId,
            correlationId = runId,
            pipelineId = pipelineUuid,
            pipelineRunId = runUuid,
            payload = mapOf("runId" to runId, "pipelineId" to pipelineId, "triggerType" to triggerType.name, "data" to data)
        )
        EventEnvelopeValidator.requireValid(envelope)
        publishPipelineInput(pipelineId, objectMapper.writeValueAsString(envelope))
    }

    private fun publishPipelineInput(pipelineId: String, message: String) {
        outbox.enqueue(getPipelineInputTopic(), pipelineId, message)
    }
}
