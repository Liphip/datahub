package com.dataturntable.api

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.websocket.*
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import org.jboss.logging.Logger

@ServerEndpoint("/api/v1/ws/{pipelineId}")
@ApplicationScoped
class WebSocketEndpoint {
    
    private val LOG = Logger.getLogger(WebSocketEndpoint::class.java)
    
    @Inject
    lateinit var webSocketIngest: WebSocketIngestResource
    
    @OnOpen
    fun onOpen(session: Session, @PathParam("pipelineId") pipelineId: String) {
        LOG.infof("WebSocket connection opened for pipeline: %s", pipelineId)
        session.maxTextMessageBufferSize = 1024 * 1024
        webSocketIngest.registerSession(pipelineId, session)
    }
    
    @OnMessage
    fun onMessage(session: Session, message: String, @PathParam("pipelineId") pipelineId: String) {
        LOG.debugf("WebSocket message received for pipeline %s: %s", pipelineId, message.take(200))
        webSocketIngest.handleWebSocketMessage(session, pipelineId, message)
    }
    
    @OnClose
    fun onClose(session: Session, closeReason: CloseReason, @PathParam("pipelineId") pipelineId: String) {
        LOG.infof("WebSocket connection closed for pipeline: %s", pipelineId)
        webSocketIngest.unregisterSession(pipelineId, session)
    }
    
    @OnError
    fun onError(session: Session, error: Throwable, @PathParam("pipelineId") pipelineId: String) {
        LOG.errorf(error, "WebSocket error for pipeline: %s", pipelineId)
        webSocketIngest.unregisterSession(pipelineId, session)
    }
}
