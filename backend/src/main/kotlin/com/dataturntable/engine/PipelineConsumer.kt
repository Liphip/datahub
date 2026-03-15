package com.dataturntable.engine

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger

data class PipelineMessage(
    val runId: String,
    val pipelineId: String,
    val triggerType: String,
    val data: Any? = null
)

@ApplicationScoped
class PipelineConsumer {
    
    private val LOG = Logger.getLogger(PipelineConsumer::class.java)
    
    @Incoming("pipeline-input")
    fun consume(message: String) {
        LOG.infof("Received pipeline message: %s", message)
    }
}
