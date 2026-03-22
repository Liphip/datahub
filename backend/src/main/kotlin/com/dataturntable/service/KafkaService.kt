package com.dataturntable.service

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class KafkaService {
    
    @ConfigProperty(name = "dataturntable.kafka.topics.prefix", defaultValue = "dataturntable")
    lateinit var topicPrefix: String
    
    @Inject
    @Channel("pipeline-commands")
    var commandEmitter: Emitter<String>? = null
    
    private val objectMapper = ObjectMapper()
    private val activeConsumers = ConcurrentHashMap<String, Boolean>()

    fun isKafkaAvailable(): Boolean = commandEmitter != null
    
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
    
    fun triggerPipeline(pipelineId: String, runId: String, triggerType: TriggerType) {
        if (commandEmitter == null) {
            println("Kafka not available, skipping pipeline trigger for: $pipelineId")
            return
        }
        
        val message = """
            {
                "runId": "$runId",
                "pipelineId": "$pipelineId",
                "triggerType": "${triggerType.name}",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withTopic(getPipelineInputTopic())
            .build()
        
        commandEmitter?.send(Message.of(message, Metadata.of(metadata)))
    }
    
    fun startPipelineConsumers(pipeline: Pipeline) {
        val consumerGroup = pipeline.kafkaConsumerGroup ?: return
        activeConsumers[consumerGroup] = true
        println("Starting consumers for pipeline: ${pipeline.id} with group: $consumerGroup")
    }
    
    fun stopPipelineConsumers(pipeline: Pipeline) {
        val consumerGroup = pipeline.kafkaConsumerGroup ?: return
        activeConsumers.remove(consumerGroup)
        println("Stopping consumers for pipeline: ${pipeline.id} with group: $consumerGroup")
    }
    
    fun isConsumerActive(consumerGroup: String): Boolean {
        return activeConsumers.containsKey(consumerGroup)
    }
    
    fun getImportTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.import"
    fun getProcessingTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.processing"
    fun getExportTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.export"
    fun getAlertsTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.alerts"
    fun getPipelineInputTopic(): String = "$topicPrefix.pipelines.input"
    fun getPipelineOutputTopic(): String = "$topicPrefix.pipelines.output"
    
    fun sendToPipelineSpecificInput(pipelineId: String, data: Any) {
        val topic = getPipelineInputTopic()
        
        val jsonData = if (data is String) {
            data
        } else {
            objectMapper.writeValueAsString(data)
        }
        
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withTopic(topic)
            .build()
        
        if (commandEmitter != null) {
            commandEmitter?.send(Message.of(jsonData, Metadata.of(metadata)))
        } else {
            println("WARNING: No Kafka emitter available, data would be lost: $jsonData")
        }
    }
    
    fun sendToPipelineInput(pipelineId: String, data: Any) {
        sendToPipelineSpecificInput(pipelineId, data)
    }

    /**
     * Trigger a pipeline run with an attached data payload (for event-driven triggers like MQTT or polling).
     * The data is embedded in the PipelineTriggerMessage so PipelineConsumer makes it available
     * to the first IMPORT module as INPUT_DATA.
     */
    fun triggerPipelineWithData(pipelineId: String, runId: String, triggerType: com.dataturntable.pipeline.TriggerType, data: Any?) {
        if (commandEmitter == null) {
            println("Kafka not available, dropping event-triggered run $runId for pipeline $pipelineId")
            return
        }
        val dataJson = if (data != null) objectMapper.writeValueAsString(data) else "null"
        val message = """{"runId":"$runId","pipelineId":"$pipelineId","triggerType":"${triggerType.name}","data":$dataJson}"""
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withTopic(getPipelineInputTopic())
            .build()
        commandEmitter?.send(Message.of(message, Metadata.of(metadata)))
    }
}
