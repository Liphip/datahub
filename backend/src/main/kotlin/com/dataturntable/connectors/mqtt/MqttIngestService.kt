package com.dataturntable.connectors.mqtt

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.service.KafkaService
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.reactive.messaging.annotations.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class MqttIngestService {
    
    private val LOG = Logger.getLogger(MqttIngestService::class.java)
    
    @Inject
    lateinit var kafkaService: KafkaService
    
    private val objectMapper = ObjectMapper()
    
    fun subscribeToPipeline(pipelineId: String, topic: String, broker: String) {
        LOG.infof("Subscribing to MQTT topic %s for pipeline %s on broker %s", topic, pipelineId, broker)
    }
    
    fun unsubscribeFromPipeline(pipelineId: String) {
        LOG.infof("Unsubscribing from pipeline %s", pipelineId)
    }
    
    @Incoming("mqtt-ingest")
    @Blocking
    fun processMqttMessage(message: String) {
        LOG.debugf("Received MQTT message: %s", message.take(200))
        
        try {
            val payload = try {
                objectMapper.readValue(message, Map::class.java) as? Map<String, Any>
            } catch (e: Exception) {
                mapOf("raw" to message)
            }
            
            val pipelineId = payload?.get("_pipelineId") as? String
            if (pipelineId != null) {
                kafkaService.sendToPipelineSpecificInput(pipelineId, payload)
                LOG.debugf("Forwarded MQTT message to pipeline %s", pipelineId)
            } else {
                LOG.warn("MQTT message has no _pipelineId, ignoring")
            }
        } catch (e: Exception) {
            LOG.errorf("Failed to process MQTT message: %s", e.message)
        }
    }
}
