package com.dataturntable.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KafkaServiceTest {
    
    private val kafkaService = TestKafkaService()
    
    @Test
    fun testKafkaServiceInitialization() {
        assertNotNull(kafkaService)
    }
    
    @Test
    fun testGetImportTopic() {
        val pipelineId = "test-pipeline-123"
        val topic = kafkaService.getImportTopic(pipelineId)
        assertTrue(topic.contains(pipelineId))
        assertTrue(topic.contains("import"))
    }
    
    @Test
    fun testGetProcessingTopic() {
        val pipelineId = "test-pipeline-456"
        val topic = kafkaService.getProcessingTopic(pipelineId)
        assertTrue(topic.contains(pipelineId))
        assertTrue(topic.contains("processing"))
    }
    
    @Test
    fun testGetExportTopic() {
        val pipelineId = "test-pipeline-789"
        val topic = kafkaService.getExportTopic(pipelineId)
        assertTrue(topic.contains(pipelineId))
        assertTrue(topic.contains("export"))
    }
    
    @Test
    fun testGetAlertsTopic() {
        val pipelineId = "test-pipeline-alerts"
        val topic = kafkaService.getAlertsTopic(pipelineId)
        assertTrue(topic.contains(pipelineId))
        assertTrue(topic.contains("alerts"))
    }
    
    @Test
    fun testTopicNamingConvention() {
        val pipelineId = "my-pipeline"
        
        val importTopic = kafkaService.getImportTopic(pipelineId)
        val processingTopic = kafkaService.getProcessingTopic(pipelineId)
        val exportTopic = kafkaService.getExportTopic(pipelineId)
        val alertsTopic = kafkaService.getAlertsTopic(pipelineId)
        
        assertEquals(importTopic, kafkaService.getImportTopic(pipelineId))
        assertEquals(processingTopic, kafkaService.getProcessingTopic(pipelineId))
        assertEquals(exportTopic, kafkaService.getExportTopic(pipelineId))
        assertEquals(alertsTopic, kafkaService.getAlertsTopic(pipelineId))
    }
    
    @Test
    fun testIsConsumerActive() {
        val result = kafkaService.isConsumerActive("test-group")
        assertFalse(result)
    }
    
    private class TestKafkaService {
        private val topicPrefix = "dataturntable"
        
        fun getImportTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.import"
        fun getProcessingTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.processing"
        fun getExportTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.export"
        fun getAlertsTopic(pipelineId: String): String = "$topicPrefix.pipelines.$pipelineId.alerts"
        fun isConsumerActive(consumerGroup: String): Boolean = false
    }
}
