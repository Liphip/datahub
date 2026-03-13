package com.dataturntable.logging

import io.vertx.core.logging.LogDelegate
import io.vertx.core.logging.Logger
import org.jboss.logging.Logger as JBLogger
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AuditLogger {
    
    private val log = JBLogger.getLogger(AuditLogger::class.java)
    
    fun logUserLogin(userId: String, tenantId: String, ipAddress: String?) {
        log.infof("USER_LOGIN: userId=%s tenantId=%s ip=%s", userId, tenantId, ipAddress)
    }
    
    fun logUserLogout(userId: String, tenantId: String) {
        log.infof("USER_LOGOUT: userId=%s tenantId=%s", userId, tenantId)
    }
    
    fun logPipelineExecution(pipelineId: String, runId: String, status: String) {
        log.infof("PIPELINE_EXECUTE: pipelineId=%s runId=%s status=%s", pipelineId, runId, status)
    }
    
    fun logModuleExecution(moduleId: String, version: Int, status: String, durationMs: Long) {
        log.infof("MODULE_EXECUTE: moduleId=%s version=%d status=%s durationMs=%d", 
            moduleId, version, status, durationMs)
    }
    
    fun logDataAccess(userId: String, tenantId: String, operation: String, resourceType: String, resourceId: String) {
        log.infof("DATA_ACCESS: userId=%s tenantId=%s operation=%s type=%s id=%s",
            userId, tenantId, operation, resourceType, resourceId)
    }
    
    fun logAlert(tenantId: String, ruleId: String, severity: String, message: String) {
        log.infof("ALERT: tenantId=%s ruleId=%s severity=%s message=%s",
            tenantId, ruleId, severity, message)
    }
    
    fun logSecurityEvent(eventType: String, userId: String?, tenantId: String?, details: String) {
        log.warnf("SECURITY: event=%s userId=%s tenantId=%s details=%s",
            eventType, userId, tenantId, details)
    }
}

@ApplicationScoped
class PerformanceLogger {
    
    private val log = JBLogger.getLogger(PerformanceLogger::class.java)
    
    fun logSlowQuery(query: String, durationMs: Long, tenantId: String) {
        log.warnf("SLOW_QUERY: durationMs=%d tenantId=%s query=%s", durationMs, tenantId, query)
    }
    
    fun logSlowPipeline(pipelineId: String, durationMs: Long, thresholdMs: Long) {
        log.warnf("SLOW_PIPELINE: pipelineId=%s durationMs=%d thresholdMs=%d",
            pipelineId, durationMs, thresholdMs)
    }
    
    fun logHighMemory(percentUsed: Double) {
        log.warnf("HIGH_MEMORY: percentUsed=%.2f%%", percentUsed)
    }
    
    fun logHighCpu(percentUsed: Double) {
        log.warnf("HIGH_CPU: percentUsed=%.2f%%", percentUsed)
    }
}
