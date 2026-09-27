package com.dataturntable.connectors.mqtt

import com.dataturntable.connectors.BrokerCredential
import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.ConnectorSecretReferenceResolver
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.security.EncryptionUtils
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.engine.PipelineRunQueue
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Transactional bean that handles incoming MQTT messages from Paho callbacks.
 *
 * Paho callbacks run on Paho's own thread. Since @Transactional only works via CDI proxy,
 * MqttConnectionManager injects this bean and calls it through the CDI proxy to ensure
 * a proper JTA transaction is created for each message.
 */
@ApplicationScoped
/**
 * Defines the class `MqttMessageRouter`, the contract implemented by this source unit.
 */
class MqttMessageRouter {

    private val LOG = Logger.getLogger(MqttMessageRouter::class.java)
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    @Inject
    lateinit var runQueue: PipelineRunQueue

    @Inject
    lateinit var directory: PipelineDirectory


    @Inject
    lateinit var secretReferenceResolver: ConnectorSecretReferenceResolver

    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Defines the class `RouteEntry`, the contract implemented by this source unit.
     */
    data class RouteEntry(val pipelineId: UUID, val topicPattern: String)

    /**
     * Called by MqttConnectionManager when a message arrives for a connector.
     * Queues the message for every active pipeline mapped to this connector/topic. No database
     * access: the PUBACK does not wait for PostgreSQL. With `deduplicateBy` fields on the
     * pipeline, a redelivered message (lost PUBACK) maps to the same run and runs once.
     */
    fun routeMessage(connectorId: UUID, topic: String, payloadStr: String, routingTable: Map<UUID, List<RouteEntry>>) {
        val entries = routingTable[connectorId] ?: return
        val matching = entries.filter { mqttTopicMatches(it.topicPattern, topic) }

        if (matching.isEmpty()) {
            LOG.debugf("MQTT message on topic '%s' for connector %s matched no active pipelines", topic, connectorId)
            return
        }

        val payload: Any? = try {
            objectMapper.readValue(payloadStr, Any::class.java)
        } catch (_: Exception) {
            payloadStr
        }

        for (entry in matching) {
            val pipeline = directory.get(entry.pipelineId)
            if (pipeline == null || !pipeline.active) continue

            val runId = runQueue.enqueue(
                pipelineId = pipeline.id,
                triggerType = TriggerType.DATA,
                data = mapOf(
                    "_mqttTopic" to topic,
                    "_connectorId" to connectorId.toString(),
                    "payload" to payload
                ),
                partitionKey = topic,
                idempotencyKey = PipelineDirectory.recordKey(payload, pipeline.deduplicateBy)?.let { "$topic|$it" },
            )
            LOG.debugf("MQTT message on topic '%s' triggered pipeline %s (run %s)", topic, pipeline.id, runId)
        }
    }

    /**
     * Builds a routing table by scanning all active pipelines for IMPORT nodes
     * that use MQTT connectors. Returns Map<connectorId, List<RouteEntry>>.
     *
     * Called by MqttConnectionManager on startup and after connector/pipeline changes.
     */
    @Transactional
    fun buildRoutingTable(): Map<UUID, List<RouteEntry>> {
        val activePipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.isActive = true",
            Pipeline::class.java
        ).resultList

        val table = mutableMapOf<UUID, MutableList<RouteEntry>>()

        for (pipeline in activePipelines) {
            val graph = parseJsonMap(pipeline.graphDefinition) ?: continue
            val nodes = graph["nodes"] as? List<*> ?: continue

            for (node in nodes) {
                val nodeMap = node as? Map<*, *> ?: continue
                val nodeType = nodeMap["type"]?.toString()?.uppercase() ?: continue
                if (nodeType != "IMPORT") continue

                val connectorIdStr = nodeMap["connectorId"]?.toString() ?: continue
                val connectorId = try { UUID.fromString(connectorIdStr) } catch (_: Exception) { continue }

                val connector = em.find(Connector::class.java, connectorId) ?: continue
                if (connector.connectorType != Connector.ConnectorType.MQTT) continue
                if (connector.direction != Connector.Direction.INPUT) continue
                if (!connector.isActive) continue

                val mqttTopic = nodeMap["mqttTopic"]?.toString()?.trim()
                if (mqttTopic.isNullOrBlank()) {
                    LOG.warnf("IMPORT node in pipeline %s has MQTT connector but no mqttTopic set — skipping", pipeline.id)
                    continue
                }

                val pipelineId = pipeline.id ?: continue
                table.getOrPut(connectorId) { mutableListOf() }
                    .add(RouteEntry(pipelineId, mqttTopic))
            }
        }

        LOG.infof("MQTT routing table built: %d connector(s), %d total route(s)",
            table.size, table.values.sumOf { it.size })
        return table
    }

    /**
     * Loads decrypted config for an MQTT connector, injecting BrokerCredential credentials
     * when the connector references one.
     */
    @Transactional
    fun resolveConnectorConfig(connectorId: UUID): Pair<Connector, Map<String, Any?>>? {
        val connector = em.find(Connector::class.java, connectorId) ?: return null
        val rawConfig = try { EncryptionUtils.decrypt(connector.config) } catch (_: Exception) { connector.config }
        val config = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(rawConfig, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            emptyMap()
        }.toMutableMap()

        val resolvedConfig = secretReferenceResolver.resolve(connector, config).toMutableMap()
        val credentialIdStr = resolvedConfig["brokerCredentialId"]?.toString()
        if (!credentialIdStr.isNullOrBlank()) {
            val credentialId = try { UUID.fromString(credentialIdStr) } catch (_: Exception) { null }
            if (credentialId != null) {
                val cred = em.find(BrokerCredential::class.java, credentialId)
                if (cred != null && cred.isActive) {
                    resolvedConfig["username"] = cred.username
                    val password = cred.passwordEncrypted?.let {
                        try { EncryptionUtils.decrypt(it) } catch (_: Exception) { null }
                    }
                    if (!password.isNullOrBlank()) resolvedConfig["password"] = password
                }
            }
        }

        return connector to resolvedConfig
    }

    /**
     * Loads all active MQTT INPUT connectors for a given tenant or across all tenants.
     */
    @Transactional
    fun findAllActiveMqttInputConnectors(): List<UUID> {
        return em.createQuery(
            "SELECT c.id FROM Connector c WHERE c.connectorType = :type AND c.direction = :dir AND c.isActive = true",
            UUID::class.java
        )
            .setParameter("type", Connector.ConnectorType.MQTT)
            .setParameter("dir", Connector.Direction.INPUT)
            .resultList
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses external or persisted input into the required representation.
     */
    private fun parseJsonMap(raw: String?): Map<String, Any?>? {
        if (raw.isNullOrBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(raw, Map::class.java) as? Map<String, Any?>
        } catch (_: Exception) { null }
    }

    companion object {
        /**
         * MQTT wildcard topic matching:
         *   + matches exactly one level (e.g., "a/+/c" matches "a/b/c")
         *   # matches zero or more levels (e.g., "a/#" matches "a", "a/b", "a/b/c")
         */
        fun mqttTopicMatches(pattern: String, topic: String): Boolean {
            if (pattern == "#") return true
            if (pattern == topic) return true
            val patternParts = pattern.split("/")
            val topicParts = topic.split("/")
            var pi = 0
            var ti = 0
            while (pi < patternParts.size && ti < topicParts.size) {
                when (patternParts[pi]) {
                    "#" -> return true // # matches everything remaining
                    "+" -> { pi++; ti++ } // + matches exactly one level
                    else -> {
                        if (patternParts[pi] != topicParts[ti]) return false
                        pi++; ti++
                    }
                }
            }
            // Both exhausted, or pattern ends with # consuming the rest
            return pi == patternParts.size && ti == topicParts.size
        }
    }
}
