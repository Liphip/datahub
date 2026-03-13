package com.dataturntable.service

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.TriggerType
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class KafkaService {
    
    @ConfigProperty(name = "dataturntable.kafka.topics.prefix", defaultValue = "dataturntable")
    lateinit var topicPrefix: String
    
    @Inject
    @Channel("pipeline-commands")
    lateinit var commandEmitter: Emitter<String>
    
    private val activeConsumers = ConcurrentHashMap<String, Boolean>()
    
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
        val message = """
            {
                "runId": "$runId",
                "pipelineId": "$pipelineId",
                "triggerType": "${triggerType.name}",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        
        val metadata = OutgoingKafkaRecordMetadata<String>()
            .withTopic("$topicPrefix.pipelines.$pipelineId.import")
        
        commandEmitter.send(Message.of(message, Metadata.of(metadata)))
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
}
