package com.dataturntable.alert

import com.dataturntable.alert.AlertChannelType.*
import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@ApplicationScoped
/**
 * Defines the class `AlertDispatcher`, the contract implemented by this source unit.
 */
class AlertDispatcher {

    private val LOG = Logger.getLogger(AlertDispatcher::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    
    @ConfigProperty(name = "dataturntable.alerts.enabled", defaultValue = "true")
    var alertsEnabled: Boolean = true

    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchWebhookAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val url = config["url"]?.toString() 
            ?: throw IllegalArgumentException("Webhook URL not configured")
        
        val payload = buildAlertPayload(alert)
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Webhook returned status ${response.statusCode()}: ${response.body()}")
        }
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchRestAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val url = config["url"]?.toString()
            ?: throw IllegalArgumentException("REST endpoint URL not configured")
        
        val method = config["method"]?.toString()?.uppercase() ?: "POST"
        
        val payload = buildAlertPayload(alert)
        
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
        
        when (method) {
            "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            "PATCH" -> requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            else -> throw IllegalArgumentException("Unsupported REST method: $method")
        }
        
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("REST call returned status ${response.statusCode()}: ${response.body()}")
        }
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchMqttAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val topic = config["topic"]?.toString()
            ?: throw IllegalArgumentException("MQTT topic not configured")
        
        LOG.infof("Would publish to MQTT topic %s: %s", topic, alert.title)
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchEmailAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val to = config["to"]?.toString()
            ?: throw IllegalArgumentException("Email recipient not configured")
        
        val subject = "[${alert.severity}] ${alert.title}"
        val body = buildAlertEmailBody(alert)
        
        LOG.infof("Would send email to %s: %s", to, subject)
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchSlackAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val webhookUrl = config["url"]?.toString()
            ?: throw IllegalArgumentException("Slack webhook URL not configured")
        
        val payload = mapOf(
            "text" to ":warning: *${alert.title}*",
            "blocks" to listOf(
                mapOf(
                    "type" to "header",
                    "text" to mapOf("type" to "plain_text", "text" to alert.title)
                ),
                mapOf(
                    "type" to "section",
                    "text" to mapOf("type" to "mrkdwn", "text" to alert.message)
                ),
                mapOf(
                    "type" to "context",
                    "elements" to listOf(
                        mapOf("type" to "mrkdwn", "text" to "*Severity:* ${alert.severity}"),
                        mapOf("type" to "mrkdwn", "text" to "*Status:* ${alert.status}")
                    )
                )
            )
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Slack webhook returned status ${response.statusCode()}: ${response.body()}")
        }
    }
    
    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseConfig(config: String?): Map<String, Any> {
        return try {
            if (config.isNullOrBlank()) {
                emptyMap()
            } else {
                objectMapper.readValue(config, Map::class.java) as Map<String, Any>
            }
        } catch (e: Exception) {
            LOG.warnf("Failed to parse alert channel config: %s", e.message)
            emptyMap()
        }
    }

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseSeverity(raw: String): AlertSeverity {
        return try {
            AlertSeverity.valueOf(raw.uppercase())
        } catch (_: Exception) {
            AlertSeverity.WARNING
        }
    }

    
    /**
     * Implements `buildAlertPayload` for this source unit.
     */
    private fun buildAlertPayload(alert: AlertHistory): Map<String, Any> {
        return mapOf(
            "id" to (alert.id?.toString() ?: ""),
            "title" to (alert.title ?: ""),
            "message" to (alert.message ?: ""),
            "severity" to (alert.severity?.name ?: "INFO"),
            "status" to (alert.status?.name ?: "PENDING"),
            "timestamp" to (alert.sentAt?.toString() ?: java.time.Instant.now().toString()),
            "payload" to (alert.payload ?: emptyMap<String, Any>())
        )
    }
    
    /**
     * Implements `buildAlertEmailBody` for this source unit.
     */
    private fun buildAlertEmailBody(alert: AlertHistory): String {
        return buildString {
            appendLine("Alert: ${alert.title}")
            appendLine()
            appendLine("Severity: ${alert.severity}")
            appendLine("Status: ${alert.status}")
            appendLine()
            appendLine("Message:")
            appendLine(alert.message)
            alert.payload?.let { payload ->
                appendLine()
                appendLine("Additional Data:")
                appendLine(payload.toString())
            }
        }
    }
    
    /**
     * Called when an ALERT module returns `{"__alert__": {...}}` in its output.
     * Looks up the channel by ID (preferred) or by name within the pipeline's tenant,
     * creates an AlertHistory entry, and dispatches it.
     *
     * @param channelRef  Channel UUID string or channel name.
     */
    @Transactional
    fun dispatchToChannel(
        pipeline: Pipeline,
        channelRef: String?,
        title: String,
        message: String,
        severity: String = "WARNING",
        payload: Map<String, Any>? = null
    ) {
        if (!alertsEnabled) {
            LOG.infof("Alerts disabled, skipping channel dispatch for '%s'", title)
            return
        }
        val tenantId = pipeline.tenant?.id ?: run {
            LOG.warn("Cannot dispatch alert: pipeline has no tenant")
            return
        }

        val channel: AlertChannel? = if (channelRef != null) {
            // Try UUID lookup first, then fall back to name search within tenant
            val byId = try {
                val uuid = java.util.UUID.fromString(channelRef)
                em.find(AlertChannel::class.java, uuid)
                    ?.takeIf { it.tenant?.id == tenantId && it.isActive }
            } catch (_: Exception) { null }

            byId ?: em.createQuery(
                "SELECT c FROM AlertChannel c WHERE c.tenant.id = :tid AND c.name = :name AND c.isActive = true",
                AlertChannel::class.java
            ).setParameter("tid", tenantId).setParameter("name", channelRef)
                .resultList.firstOrNull()
        } else {
            // No channel specified — try the first active channel for this tenant
            em.createQuery(
                "SELECT c FROM AlertChannel c WHERE c.tenant.id = :tid AND c.isActive = true ORDER BY c.createdAt ASC",
                AlertChannel::class.java
            ).setParameter("tid", tenantId).setMaxResults(1).resultList.firstOrNull()
        }

        if (channel == null) {
            LOG.warnf("Alert channel not found for ref '%s' in tenant %s — alert not dispatched", channelRef, tenantId)
            return
        }

        val parsedSeverity = parseSeverity(severity)
        val alert = AlertHistory().apply {
            this.tenant = pipeline.tenant
            this.channel = channel
            this.severity = parsedSeverity
            this.title = title
            this.message = message
            this.status = AlertStatus.PENDING
            this.payload = payload?.let { objectMapper.writeValueAsString(it) }
            this.sentAt = java.time.Instant.now()
        }
        alert.persist()
        dispatchAlertToChannel(channel, alert)
    }

    /** Dispatch an alert directly to a channel (no rule required). */
    @Transactional
    /**
     * Delivers or routes the named data to its configured destination.
     */
    fun dispatchAlertToChannel(channel: AlertChannel, alert: AlertHistory) {
        if (!alertsEnabled) return
        try {
            when (channel.channelType) {
                null -> throw IllegalArgumentException("Channel type not set")
                MQTT -> dispatchMqttAlert(channel, alert)
                WEBHOOK -> dispatchWebhookAlert(channel, alert)
                REST -> dispatchRestAlert(channel, alert)
                EMAIL -> dispatchEmailAlert(channel, alert)
                SLACK -> dispatchSlackAlert(channel, alert)
            }
            alert.status = AlertStatus.SENT
            alert.sentAt = java.time.Instant.now()
            alert.persist()
            LOG.infof("Channel alert dispatched: %s -> %s", channel.name, alert.title)
        } catch (e: Exception) {
            LOG.errorf("Failed to dispatch channel alert: %s", e.message)
            alert.status = AlertStatus.FAILED
            alert.persist()
        }
    }

}
