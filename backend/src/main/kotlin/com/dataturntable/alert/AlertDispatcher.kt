package com.dataturntable.alert

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class AlertDispatcher {
    
    private val LOG = Logger.getLogger(AlertDispatcher::class.java)
    
    fun dispatchAlert(alert: AlertHistory) {
        LOG.infof("Dispatching alert: %s", alert.title)
    }
}
