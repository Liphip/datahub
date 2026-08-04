package com.dataturntable.logging

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
/**
 * Defines the class `AuditLogger`, the contract implemented by this source unit.
 */
class AuditLogger {
    
    private val log = Logger.getLogger(AuditLogger::class.java)
    
    /**
     * Implements `logUserLogin` for this source unit.
     */
    fun logUserLogin(userId: String, tenantId: String, ipAddress: String?) {
        log.infof("USER_LOGIN: userId=%s tenantId=%s ip=%s", userId, tenantId, ipAddress)
    }
    
    /**
     * Implements `logUserLogout` for this source unit.
     */
    fun logUserLogout(userId: String, tenantId: String) {
        log.infof("USER_LOGOUT: userId=%s tenantId=%s", userId, tenantId)
    }
    
    /**
     * Implements `logPipelineExecution` for this source unit.
     */
    fun logPipelineExecution(pipelineId: String, runId: String, status: String) {
        log.infof("PIPELINE_EXECUTE: pipelineId=%s runId=%s status=%s", pipelineId, runId, status)
    }
    
    /**
     * Implements `logModuleExecution` for this source unit.
     */
    fun logModuleExecution(moduleId: String, version: Int, status: String, durationMs: Long) {
        log.infof("MODULE_EXECUTE: moduleId=%s version=%d status=%s durationMs=%d", 
            moduleId, version, status, durationMs)
    }
    
    /**
     * Implements `logDataAccess` for this source unit.
     */
    fun logDataAccess(userId: String, tenantId: String, operation: String, resourceType: String, resourceId: String) {
        log.infof("DATA_ACCESS: userId=%s tenantId=%s operation=%s type=%s id=%s",
            userId, tenantId, operation, resourceType, resourceId)
    }
    
    /**
     * Implements `logAlert` for this source unit.
     */
    fun logAlert(tenantId: String, ruleId: String, severity: String, message: String) {
        log.infof("ALERT: tenantId=%s ruleId=%s severity=%s message=%s",
            tenantId, ruleId, severity, message)
    }
    
    /**
     * Implements `logSecurityEvent` for this source unit.
     */
    fun logSecurityEvent(eventType: String, userId: String?, tenantId: String?, details: String) {
        log.warnf("SECURITY: event=%s userId=%s tenantId=%s details=%s",
            eventType, userId, tenantId, details)
    }
}

@ApplicationScoped
/**
 * Defines the class `PerformanceLogger`, the contract implemented by this source unit.
 */
class PerformanceLogger {
    
    private val log = Logger.getLogger(PerformanceLogger::class.java)
    
    /**
     * Implements `logSlowQuery` for this source unit.
     */
    fun logSlowQuery(query: String, durationMs: Long, tenantId: String) {
        log.warnf("SLOW_QUERY: durationMs=%d tenantId=%s query=%s", durationMs, tenantId, query)
    }
    
    /**
     * Implements `logSlowPipeline` for this source unit.
     */
    fun logSlowPipeline(pipelineId: String, durationMs: Long, thresholdMs: Long) {
        log.warnf("SLOW_PIPELINE: pipelineId=%s durationMs=%d thresholdMs=%d",
            pipelineId, durationMs, thresholdMs)
    }
    
    /**
     * Implements `logHighMemory` for this source unit.
     */
    fun logHighMemory(percentUsed: Double) {
        log.warnf("HIGH_MEMORY: percentUsed=%.2f%%", percentUsed)
    }
    
    /**
     * Implements `logHighCpu` for this source unit.
     */
    fun logHighCpu(percentUsed: Double) {
        log.warnf("HIGH_CPU: percentUsed=%.2f%%", percentUsed)
    }
}
