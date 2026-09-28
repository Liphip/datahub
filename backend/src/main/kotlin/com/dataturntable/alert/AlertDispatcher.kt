package com.dataturntable.alert

import com.dataturntable.alert.AlertChannelType.*
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.connectors.mqtt.MqttConnectionManager
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import com.dataturntable.auth.User
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.security.SensitiveDataRedactor
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import com.dataturntable.config.RuntimeRoleConfig
import java.time.Duration
import java.time.Instant
import java.util.UUID
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
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    
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

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var secretReferenceResolver: AlertChannelSecretReferenceResolver

    @Inject
    lateinit var redactor: SensitiveDataRedactor

    /** Delivery attempts before an alert is marked FAILED (about 13 h with the default backoff). */
    @ConfigProperty(name = "dataturntable.alerts.max-attempts", defaultValue = "12")
    var maxAttempts: Int = 12

    /** A claim older than this belongs to a replica that died while delivering. */
    @ConfigProperty(name = "dataturntable.alerts.claim-timeout", defaultValue = "2m")
    lateinit var claimTimeout: Duration

    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchWebhookAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = resolveChannelConfig(channel)
        val url = config["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Webhook URL not configured")
        sendJson(url, config["method"]?.toString(), config["authToken"]?.toString(), buildAlertPayload(alert), alert.id?.toString())
    }
    
    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun dispatchRestAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = resolveChannelConfig(channel)
        val baseUrl = config["baseUrl"]?.toString()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("REST base URL not configured")
        val path = config["path"]?.toString()?.trim().orEmpty()
        val url = if (path.isEmpty()) baseUrl else baseUrl + "/" + path.trimStart('/')
        sendJson(url, config["method"]?.toString(), config["authToken"]?.toString(), buildAlertPayload(alert), alert.id?.toString())
    }

    /** Sends a JSON body with POST (default), PUT or PATCH; optional Bearer token. Non-2xx fails the delivery. */
    private fun sendJson(url: String, method: String?, authToken: String?, body: Any, idempotencyKey: String?) {
        val json = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
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
        val config = resolveChannelConfig(channel)
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
        val config = resolveChannelConfig(channel)
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
        val config = resolveChannelConfig(channel)
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
            .timeout(Duration.ofSeconds(15))
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
            "timestamp" to alert.createdAt.toString(),
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
    
    // ── Outbox ────────────────────────────────────────────────────────────────
    //
    // Alerts are written to alert_history (the outbox) in the same transaction as the work
    // that raised them — rule state, absence claim, or module result — and delivered
    // afterwards. The unique delivery key makes enqueueing idempotent: work repeated after a
    // failover finds its alerts already there. Delivery claims a row atomically, so each
    // alert is handed to its channel by one replica; if that replica dies mid-delivery the
    // claim expires and another one retries. Receivers get the alert id as Idempotency-Key
    // (webhook/REST) to drop the rare repeat of a delivery whose confirmation was lost.

    /**
     * Adds an alert to the outbox inside the caller's transaction. Returns the alert id, or
     * null when alerts are disabled. An alert with an existing [deliveryKey] is not added again.
     * Within the cooldown of the same alert ([dedupKey]) on the same channel it is recorded as
     * SUPPRESSED instead of being delivered.
     */
    fun enqueue(
        pipeline: Pipeline,
        channelRef: String?,
        title: String,
        message: String,
        severity: String = "WARNING",
        payload: Map<String, Any?>? = null,
        dedupKey: String? = null,
        cooldownSeconds: Long? = null,
        deliveryKey: String? = null,
    ): UUID? {
        val tenant = pipeline.tenant ?: throw IllegalStateException("Pipeline ${pipeline.id} has no tenant")
        val tenantId = tenant.id ?: throw IllegalStateException("Tenant of pipeline ${pipeline.id} has no id")
        return enqueueInternal(
            tenant = tenant,
            pipeline = pipeline,
            channel = resolveChannel(tenantId, channelRef),
            channelLabel = channelRef ?: "(default)",
            title = title, message = message, severity = severity, payload = payload,
            dedupKey = dedupKey, cooldownSeconds = cooldownSeconds, deliveryKey = deliveryKey,
        )
    }

    /**
     * Enqueues a platform alert that belongs to a tenant but to no pipeline and no node.
     *
     * It goes through the same outbox as every other alert, so cooldown suppression, retry with
     * backoff, and the FAILED state that [PlatformHealthMonitor] reports as "could not be
     * delivered" all work unchanged. `channel` is passed in because there is no ALERT node to
     * reference a channel by.
     */
    fun enqueuePlatformAlert(
        tenant: Tenant,
        channel: AlertChannel?,
        title: String,
        message: String,
        severity: String = "WARNING",
        payload: Map<String, Any?>? = null,
        dedupKey: String? = null,
        cooldownSeconds: Long? = null,
        deliveryKey: String? = null,
    ): UUID? =
        enqueueInternal(
            tenant = tenant,
            pipeline = null,
            channel = channel,
            channelLabel = channel?.name ?: "(no channel)",
            title = title, message = message, severity = severity, payload = payload,
            dedupKey = dedupKey, cooldownSeconds = cooldownSeconds, deliveryKey = deliveryKey,
        )

    private fun enqueueInternal(
        tenant: Tenant,
        pipeline: Pipeline?,
        channel: AlertChannel?,
        channelLabel: String,
        title: String,
        message: String,
        severity: String,
        payload: Map<String, Any?>?,
        dedupKey: String?,
        cooldownSeconds: Long?,
        deliveryKey: String?,
    ): UUID? {
        if (!alertsEnabled) {
            LOG.debugf("Alerts disabled, not enqueueing '%s'", title)
            return null
        }
        deliveryKey?.let { key ->
            em.createQuery("SELECT a.id FROM AlertHistory a WHERE a.deliveryKey = :k", UUID::class.java)
                .setParameter("k", key).resultList.firstOrNull()?.let { return it }
        }
        val cooldown = cooldownSeconds ?: defaultCooldownSeconds
        val suppressed = channel != null && dedupKey != null && cooldown > 0 && isInCooldown(channel, dedupKey, cooldown)
        val alert = AlertHistory().apply {
            this.tenant = tenant
            this.channel = channel
            this.pipeline = pipeline
            this.dedupKey = dedupKey
            this.deliveryKey = deliveryKey
            this.severity = parseSeverity(severity)
            this.title = title
            this.message = message
            this.payload = payload?.filterValues { it != null }?.let { objectMapper.writeValueAsString(it) }
            this.createdAt = Instant.now()
            when {
                channel == null -> {
                    status = AlertStatus.FAILED
                    lastError = "Alert channel '$channelLabel' not found or inactive"
                    LOG.warnf("Alert '%s': %s", title, lastError)
                }
                suppressed -> status = AlertStatus.SUPPRESSED
                else -> {
                    status = AlertStatus.PENDING
                    nextAttemptAt = createdAt
                }
            }
        }
        em.persist(alert)
        em.flush()
        return alert.id
    }

    /** Enqueues an alert of a built-in rule of an ALERT node (cooldown per node, title and series). */
    fun enqueueRuleAlert(pipeline: Pipeline, nodeId: String, channelRef: String?, rule: AlertRuleConfig, alert: RuleAlert, deliveryKey: String?): UUID? =
        enqueue(
            pipeline = pipeline,
            channelRef = channelRef?.takeIf { it.isNotBlank() },
            title = alert.title,
            message = alert.message,
            severity = alert.severity,
            payload = alert.payload,
            dedupKey = "${pipeline.id}:$nodeId:${alert.title}:${alert.seriesKey}",
            cooldownSeconds = rule.cooldownSeconds,
            deliveryKey = deliveryKey,
        )

    /** Enqueues in its own transaction and delivers right away (alerts raised outside a transaction). */
    fun dispatchToChannel(
        pipeline: Pipeline,
        channelRef: String?,
        title: String,
        message: String,
        severity: String = "WARNING",
        payload: Map<String, Any?>? = null,
        dedupKey: String? = null,
        cooldownSeconds: Long? = null,
        deliveryKey: String? = null,
    ) {
        val id = QuarkusTransaction.requiringNew().call {
            enqueue(em.find(Pipeline::class.java, pipeline.id), channelRef, title, message, severity, payload, dedupKey, cooldownSeconds, deliveryKey)
        } ?: return
        deliver(id)
    }

    /**
     * Delivers one outbox entry if it is due and not being delivered by another replica.
     * Returns true when this call sent it.
     */
    fun deliver(alertId: UUID): Boolean {
        if (!alertsEnabled) return false
        val now = Instant.now()
        val claimed = QuarkusTransaction.requiringNew().call {
            em.createQuery(
                "UPDATE AlertHistory a SET a.status = :sending, a.claimedAt = :now, a.attempts = a.attempts + 1 " +
                    "WHERE a.id = :id AND ((a.status = :pending AND (a.nextAttemptAt IS NULL OR a.nextAttemptAt <= :now)) " +
                    "OR (a.status = :sending AND a.claimedAt < :stale))"
            ).setParameter("sending", AlertStatus.SENDING).setParameter("pending", AlertStatus.PENDING)
                .setParameter("now", now).setParameter("stale", now.minus(claimTimeout))
                .setParameter("id", alertId).executeUpdate() == 1
        }
        if (!claimed) return false
        return try {
            QuarkusTransaction.requiringNew().run {
                val alert = em.find(AlertHistory::class.java, alertId)
                val channel = alert.channel ?: throw IllegalStateException("Alert has no channel")
                send(channel, alert)
                alert.status = AlertStatus.SENT
                alert.sentAt = Instant.now()
                alert.lastError = null
            }
            LOG.infof("Alert %s delivered", alertId)
            true
        } catch (e: Exception) {
            QuarkusTransaction.requiringNew().run {
                val alert = em.find(AlertHistory::class.java, alertId) ?: return@run
                alert.lastError = redactor.text(e.message)?.take(2000)
                if (alert.attempts >= maxAttempts) {
                    alert.status = AlertStatus.FAILED
                } else {
                    alert.status = AlertStatus.PENDING
                    alert.nextAttemptAt = Instant.now().plus(backoff(alert.attempts))
                }
            }
            LOG.warnf("Alert %s delivery failed (%s), will retry unless attempts are exhausted", alertId, redactor.text(e.message))
            false
        }
    }

    /** Delivers every due outbox entry, including ones whose delivering replica died. */
    fun deliverDue(limit: Int = 200): Int {
        val now = Instant.now()
        val due = QuarkusTransaction.requiringNew().call {
            em.createQuery(
                "SELECT a.id FROM AlertHistory a WHERE (a.status = :pending AND a.nextAttemptAt <= :now) " +
                    "OR (a.status = :sending AND a.claimedAt < :stale) ORDER BY a.createdAt",
                UUID::class.java
            ).setParameter("pending", AlertStatus.PENDING).setParameter("sending", AlertStatus.SENDING)
                .setParameter("now", now).setParameter("stale", now.minus(claimTimeout))
                .setMaxResults(limit).resultList
        }
        return due.count { deliver(it) }
    }

    @Scheduled(every = "\${dataturntable.alerts.outbox-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledDelivery() {
        if (!runtimeRoleConfig.ownsPipelineRuntime()) return
        try {
            deliverDue()
        } catch (e: Exception) {
            LOG.warnf("Alert outbox delivery failed: %s", redactor.text(e.message))
        }
    }

    /** 10 s, 30 s, 1.5 min, … capped at 1 h. */
    private fun backoff(attempts: Int): Duration =
        Duration.ofSeconds((10.0 * Math.pow(3.0, (attempts - 1).coerceAtLeast(0).toDouble())).toLong().coerceAtMost(3600))

    /** The tenant's default channel: its first active one, as an ALERT node without a reference gets. */
    fun defaultChannel(tenant: Tenant): AlertChannel? =
        resolveChannel(tenant.id ?: return null, null)

    private fun resolveChannel(tenantId: UUID, channelRef: String?): AlertChannel? =
        if (channelRef != null) {
            runCatching { UUID.fromString(channelRef) }.getOrNull()
                ?.let { em.find(AlertChannel::class.java, it) }
                ?.takeIf { it.tenant?.id == tenantId && it.isActive }
                ?: em.createQuery(
                    "SELECT c FROM AlertChannel c WHERE c.tenant.id = :tid AND c.name = :name AND c.isActive = true",
                    AlertChannel::class.java
                ).setParameter("tid", tenantId).setParameter("name", channelRef).resultList.firstOrNull()
        } else {
            // No channel specified — the tenant's first active channel.
            em.createQuery(
                "SELECT c FROM AlertChannel c WHERE c.tenant.id = :tid AND c.isActive = true ORDER BY c.createdAt ASC",
                AlertChannel::class.java
            ).setParameter("tid", tenantId).setMaxResults(1).resultList.firstOrNull()
        }

    private fun isInCooldown(channel: AlertChannel, dedupKey: String, cooldownSeconds: Long): Boolean =
        em.createQuery(
            "SELECT COUNT(a) FROM AlertHistory a WHERE a.channel = :channel AND a.dedupKey = :key " +
                "AND a.status IN :active AND a.createdAt > :since",
            java.lang.Long::class.java
        )
            .setParameter("channel", channel)
            .setParameter("key", dedupKey)
            .setParameter("active", listOf(AlertStatus.PENDING, AlertStatus.SENDING, AlertStatus.SENT))
            .setParameter("since", Instant.now().minusSeconds(cooldownSeconds))
            .singleResult.toLong() > 0

    private fun send(channel: AlertChannel, alert: AlertHistory) {
        when (channel.channelType) {
            null -> throw IllegalArgumentException("Channel type not set")
            MQTT -> dispatchMqttAlert(channel, alert)
            WEBHOOK -> dispatchWebhookAlert(channel, alert)
            REST -> dispatchRestAlert(channel, alert)
            EMAIL -> dispatchEmailAlert(channel, alert)
            SLACK -> dispatchSlackAlert(channel, alert)
        }
    }

    /** Channel config with its `secretRefs` resolved to the secret values (in memory only). */
    private fun resolveChannelConfig(channel: AlertChannel): Map<String, Any> =
        secretReferenceResolver.resolve(channel, parseConfig(channel.config))
}
