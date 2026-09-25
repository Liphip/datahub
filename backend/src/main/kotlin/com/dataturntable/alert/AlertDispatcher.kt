package com.dataturntable.alert

import com.dataturntable.alert.AlertChannelType.*
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.connectors.mqtt.MqttConnectionManager
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
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

    /** Default suppression window for repeats of the same alert; 0 disables cooldown. */
    @ConfigProperty(name = "dataturntable.alerts.default-cooldown-seconds", defaultValue = "300")
    var defaultCooldownSeconds: Long = 300

    @Inject
    lateinit var mailer: Mailer

    @Inject
    lateinit var connectorRuntime: ConnectorRuntimeService

    @Inject
    lateinit var mqttConnectionManager: MqttConnectionManager

    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchWebhookAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val url = config["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Webhook URL not configured")
        sendJson(url, config["method"]?.toString(), config["authToken"]?.toString(), buildAlertPayload(alert))
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchRestAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val baseUrl = config["baseUrl"]?.toString()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("REST base URL not configured")
        val path = config["path"]?.toString()?.trim().orEmpty()
        val url = if (path.isEmpty()) baseUrl else baseUrl + "/" + path.trimStart('/')
        sendJson(url, config["method"]?.toString(), config["authToken"]?.toString(), buildAlertPayload(alert))
    }

    /** Sends a JSON body with POST (default), PUT or PATCH; optional Bearer token. Non-2xx fails the delivery. */
    private fun sendJson(url: String, method: String?, authToken: String?, body: Any) {
        val json = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
        authToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        when (val verb = method?.trim()?.uppercase()?.ifEmpty { null } ?: "POST") {
            "POST" -> builder.POST(json)
            "PUT" -> builder.PUT(json)
            "PATCH" -> builder.method("PATCH", json)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $verb")
        }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("$url returned status ${response.statusCode()}: ${response.body().take(300)}")
        }
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchMqttAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val connectorId = config["connectorId"]?.toString()
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: throw IllegalArgumentException("MQTT alert channel needs connectorId (an MQTT OUTPUT connector)")
        val connector = em.find(Connector::class.java, connectorId)
            ?.takeIf { it.tenant?.id == channel.tenant?.id && it.connectorType == Connector.ConnectorType.MQTT }
            ?: throw IllegalArgumentException("MQTT connector $connectorId not found in the channel's tenant")
        val topic = config["topic"]?.toString()?.takeIf { it.isNotBlank() }
        mqttConnectionManager.publish(
            connectorId,
            connectorRuntime.resolveRuntimeConfig(connector),
            topic,
            objectMapper.writeValueAsString(buildAlertPayload(alert))
        )
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchEmailAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val recipients = config["recipient"]?.toString()
            ?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Email recipient not configured")
        val subject = (config["subjectTemplate"]?.toString()?.takeIf { it.isNotBlank() } ?: "[{severity}] {title}")
            .replace("{severity}", alert.severity?.name ?: "")
            .replace("{title}", alert.title ?: "")
        // SMTP server, sender and credentials come from the installation's quarkus.mailer.* settings.
        mailer.send(*recipients.map { Mail.withText(it, subject, buildAlertEmailBody(alert)) }.toTypedArray())
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchSlackAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val webhookUrl = config["webhookUrl"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Slack webhook URL not configured")
        
        val payload = listOfNotNull(
            config["channel"]?.toString()?.takeIf { it.isNotBlank() }?.let { "channel" to it },
        ).toMap() + mapOf(
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
            "payload" to (alert.payload?.let { objectMapper.readValue(it, Any::class.java) } ?: emptyMap<String, Any>())
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
        payload: Map<String, Any>? = null,
        dedupKey: String? = null,
        cooldownSeconds: Long? = null,
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

        val cooldown = cooldownSeconds ?: defaultCooldownSeconds
        if (dedupKey != null && cooldown > 0 && isInCooldown(channel, dedupKey, cooldown)) {
            LOG.infof("Alert '%s' suppressed: same alert sent to channel %s within %d s", title, channel.id, cooldown)
            return
        }

        val parsedSeverity = parseSeverity(severity)
        val alert = AlertHistory().apply {
            this.tenant = pipeline.tenant
            this.channel = channel
            this.pipeline = pipeline
            this.dedupKey = dedupKey
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

    private fun isInCooldown(channel: AlertChannel, dedupKey: String, cooldownSeconds: Long): Boolean =
        em.createQuery(
            "SELECT COUNT(a) FROM AlertHistory a WHERE a.channel = :channel AND a.dedupKey = :key " +
                "AND a.status = :sent AND a.sentAt > :since",
            java.lang.Long::class.java
        )
            .setParameter("channel", channel)
            .setParameter("key", dedupKey)
            .setParameter("sent", AlertStatus.SENT)
            .setParameter("since", java.time.Instant.now().minusSeconds(cooldownSeconds))
            .singleResult.toLong() > 0

    /** Dispatch an alert directly to a channel. */
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
