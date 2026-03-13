package com.dataturntable.alert

import com.dataturntable.alert.AlertChannelType.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

@ApplicationScoped
class AlertDispatcher {
    
    private val LOG = Logger.getLogger(AlertDispatcher::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var mqttAlertSender: MqttAlertSender
    
    @Inject
    lateinit var webhookAlertSender: WebhookAlertSender
    
    @Inject
    lateinit var restAlertSender: RestAlertSender
    
    @Inject
    lateinit var emailAlertSender: EmailAlertSender
    
    @Transactional
    fun dispatchAlert(alert: AlertHistory) {
        val channel = alert.channel ?: run {
            LOG.warn("Alert has no channel configured")
            return
        }
        
        val result = when (channel.channelType) {
            MQTT -> mqttAlertSender.send(alert, channel)
            WEBHOOK -> webhookAlertSender.send(alert, channel)
            REST -> restAlertSender.send(alert, channel)
            EMAIL -> emailAlertSender.send(alert, channel)
            SLACK -> webhookAlertSender.send(alert, channel) // Reuse webhook for Slack
            null -> {
                LOG.error("Unknown channel type")
                alert.status = AlertStatus.FAILED
                return
            }
        }
        
        alert.status = if (result) AlertStatus.SENT else AlertStatus.FAILED
        alert.persist()
    }
}

interface AlertSender {
    fun send(alert: AlertHistory, channel: AlertChannel): Boolean
}

@ApplicationScoped
class MqttAlertSender : AlertSender {
    private val LOG = Logger.getLogger(MqttAlertSender::class.java)
    
    override fun send(alert: AlertHistory, channel: AlertChannel): Boolean {
        return try {
            val config = parseConfig(channel.config)
            val topic = config["topic"] as? String ?: "dataturntable/alerts"
            
            LOG.infof("Would send MQTT alert to topic: %s", topic)
            // In production, use MQTT client to publish
            true
        } catch (e: Exception) {
            LOG.error("Failed to send MQTT alert", e)
            false
        }
    }
}

@ApplicationScoped
class WebhookAlertSender : AlertSender {
    private val LOG = Logger.getLogger(WebhookAlertSender::class.java)
    
    override fun send(alert: AlertHistory, channel: AlertChannel): Boolean {
        return try {
            val config = parseConfig(channel.config)
            val url = config["url"] as? String ?: throw IllegalArgumentException("URL is required")
            
            LOG.infof("Would send webhook alert to: %s", url)
            // In production, use HTTP client to POST
            true
        } catch (e: Exception) {
            LOG.error("Failed to send webhook alert", e)
            false
        }
    }
}

@ApplicationScoped
class RestAlertSender : AlertSender {
    private val LOG = Logger.getLogger(RestAlertSender::class.java)
    
    override fun send(alert: AlertHistory, channel: AlertChannel): Boolean {
        return try {
            val config = parseConfig(channel.config)
            val url = config["url"] as? String ?: throw IllegalArgumentException("URL is required")
            val method = config["method"] as? String ?: "POST"
            
            LOG.infof("Would send REST alert to: %s with method: %s", url, method)
            // In production, use HTTP client
            true
        } catch (e: Exception) {
            LOG.error("Failed to send REST alert", e)
            false
        }
    }
}

@ApplicationScoped
class EmailAlertSender : AlertSender {
    private val LOG = Logger.getLogger(EmailAlertSender::class.java)
    
    override fun send(alert: AlertHistory, channel: AlertChannel): Boolean {
        return try {
            val config = parseConfig(channel.config)
            val to = config["to"] as? String ?: throw IllegalArgumentException("Recipient is required")
            val from = config["from"] as? String ?: "noreply@dataturntable.io"
            val subject = config["subject"] as? String ?: "DataTurntable Alert"
            
            LOG.infof("Would send email from: %s to: %s with subject: %s", from, to, subject)
            // In production, use JavaMail or Quarkus mailer
            true
        } catch (e: Exception) {
            LOG.error("Failed to send email alert", e)
            false
        }
    }
}

fun parseConfig(config: String?): Map<String, Any> {
    if (config.isNullOrBlank()) return emptyMap()
    return try {
        objectMapper.readValue(config, Map::class.java) as? Map<String, Any> ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}
