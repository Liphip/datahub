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
class AlertDispatcher {
    
    private val LOG = Logger.getLogger(AlertDispatcher::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    
    @ConfigProperty(name = "dataturntable.alerts.enabled", defaultValue = "true")
    var alertsEnabled: Boolean = true

    @PersistenceContext
    lateinit var em: EntityManager

    @Transactional
    fun evaluateRulesForPipeline(pipeline: Pipeline, payload: Map<String, Any>?): Int {
        val pipelineId = pipeline.id ?: return 0
        val tenantId = pipeline.tenant?.id ?: return 0

        val rules = em.createQuery(
            "SELECT r FROM AlertRule r WHERE r.enabled = true AND r.tenant.id = :tenantId AND (r.pipeline IS NULL OR r.pipeline.id = :pipelineId)",
            AlertRule::class.java
        )
            .setParameter("tenantId", tenantId)
            .setParameter("pipelineId", pipelineId)
            .resultList

        var fired = 0
        for (rule in rules) {
            if (!matchesCondition(rule.condition, payload)) continue
            if (isInCooldown(rule)) continue

            val condition = parseCondition(rule.condition)
            val severity = parseSeverity((condition["severity"] ?: "WARNING").toString())
            val message = (condition["message"] as? String)
                ?: "Rule '${rule.name ?: rule.id}' matched pipeline data"

            createAlertFromRule(rule, pipeline, severity, message, payload)
            fired++
        }

        return fired
    }

    fun matchesCondition(conditionRaw: String?, payload: Map<String, Any>?): Boolean {
        val condition = parseCondition(conditionRaw)
        return evaluateConditionMap(condition, payload ?: emptyMap())
    }
    
    @Transactional
    fun dispatchAlert(alert: AlertHistory) {
        if (!alertsEnabled) {
            LOG.infof("Alerts disabled, skipping dispatch for alert: %s", alert.title)
            return
        }
        
        val rule = alert.rule
        if (rule == null) {
            LOG.warnf("Alert %s has no associated rule", alert.id)
            return
        }
        
        val channel = rule.channel
        if (channel == null) {
            LOG.warnf("Alert rule %s has no associated channel", rule.id)
            return
        }
        
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
            
            LOG.infof("Alert dispatched successfully: %s", alert.title)
        } catch (e: Exception) {
            LOG.errorf("Failed to dispatch alert: %s", e.message)
            alert.status = AlertStatus.FAILED
            alert.persist()
        }
    }
    
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
    
    private fun dispatchMqttAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val topic = config["topic"]?.toString()
            ?: throw IllegalArgumentException("MQTT topic not configured")
        
        LOG.infof("Would publish to MQTT topic %s: %s", topic, alert.title)
    }
    
    private fun dispatchEmailAlert(channel: AlertChannel, alert: AlertHistory) {
        val config = parseConfig(channel.config)
        val to = config["to"]?.toString()
            ?: throw IllegalArgumentException("Email recipient not configured")
        
        val subject = "[${alert.severity}] ${alert.title}"
        val body = buildAlertEmailBody(alert)
        
        LOG.infof("Would send email to %s: %s", to, subject)
    }
    
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

    private fun parseCondition(conditionRaw: String?): Map<String, Any> {
        if (conditionRaw.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(conditionRaw, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun evaluateConditionMap(condition: Map<String, Any>, payload: Map<String, Any>): Boolean {
        if (condition.isEmpty()) return false

        val all = condition["all"] as? List<*>
        if (all != null) {
            return all.all {
                val nested = it as? Map<*, *> ?: return@all false
                @Suppress("UNCHECKED_CAST")
                evaluateConditionMap(nested as Map<String, Any>, payload)
            }
        }

        val any = condition["any"] as? List<*>
        if (any != null) {
            return any.any {
                val nested = it as? Map<*, *> ?: return@any false
                @Suppress("UNCHECKED_CAST")
                evaluateConditionMap(nested as Map<String, Any>, payload)
            }
        }

        val field = condition["field"]?.toString() ?: return false
        val operator = (condition["operator"]?.toString() ?: "==").lowercase()
        val expected = condition["value"]
        val actual = resolvePayloadValue(payload, field)

        return when (operator) {
            "exists" -> actual != null
            "==", "eq", "equals" -> compareValues(actual, expected) == 0
            "!=", "neq", "not_equals" -> compareValues(actual, expected) != 0
            ">", "gt" -> compareValues(actual, expected) > 0
            ">=", "gte" -> compareValues(actual, expected) >= 0
            "<", "lt" -> compareValues(actual, expected) < 0
            "<=", "lte" -> compareValues(actual, expected) <= 0
            "contains" -> actual?.toString()?.contains(expected?.toString() ?: "", ignoreCase = true) == true
            else -> false
        }
    }

    private fun resolvePayloadValue(payload: Map<String, Any>, fieldPath: String): Any? {
        val segments = fieldPath.split('.')
        var current: Any? = payload
        for (segment in segments) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    private fun compareValues(actual: Any?, expected: Any?): Int {
        if (actual == null && expected == null) return 0
        if (actual == null) return -1
        if (expected == null) return 1

        val aNum = actual.toString().toDoubleOrNull()
        val eNum = expected.toString().toDoubleOrNull()
        if (aNum != null && eNum != null) {
            return aNum.compareTo(eNum)
        }

        return actual.toString().compareTo(expected.toString(), ignoreCase = true)
    }

    private fun parseSeverity(raw: String): AlertSeverity {
        return try {
            AlertSeverity.valueOf(raw.uppercase())
        } catch (_: Exception) {
            AlertSeverity.WARNING
        }
    }

    private fun isInCooldown(rule: AlertRule): Boolean {
        if (rule.cooldownSeconds <= 0) return false
        val latest = em.createQuery(
            "SELECT a FROM AlertHistory a WHERE a.rule.id = :ruleId ORDER BY a.sentAt DESC",
            AlertHistory::class.java
        )
            .setParameter("ruleId", rule.id)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
            ?: return false

        val elapsed = java.time.Duration.between(latest.sentAt, java.time.Instant.now()).seconds
        return elapsed < rule.cooldownSeconds
    }
    
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
    
    fun createAlertFromRule(
        rule: AlertRule,
        pipeline: Pipeline,
        severity: AlertSeverity,
        message: String,
        payload: Map<String, Any>?
    ): AlertHistory {
        val tenant = pipeline.tenant
            ?: throw IllegalArgumentException("Pipeline has no tenant")
        
        val alert = AlertHistory().apply {
            this.tenant = tenant
            this.rule = rule
            this.severity = severity
            this.title = rule.name
            this.message = message
            this.status = AlertStatus.PENDING
            this.payload = payload?.let { objectMapper.writeValueAsString(it) }
        }
        
        alert.persist()
        
        dispatchAlert(alert)
        
        return alert
    }
}
