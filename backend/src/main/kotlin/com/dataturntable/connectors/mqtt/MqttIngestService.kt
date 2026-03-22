package com.dataturntable.connectors.mqtt

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.util.UUID

/**
 * MqttIngestService is a thin facade over MqttConnectionManager.
 *
 * MQTT connectivity is managed dynamically by MqttConnectionManager using Eclipse Paho.
 * The old @Incoming("mqtt-ingest") approach required a statically-configured broker in
 * application.yaml and could not support multiple brokers or dynamic per-connector subscriptions.
 *
 * This class is retained for API compatibility with ConnectorResource, which calls
 * refreshConnector() / refreshAll() after connector CRUD operations.
 */
@ApplicationScoped
class MqttIngestService {

    private val LOG = Logger.getLogger(MqttIngestService::class.java)

    @Inject
    lateinit var mqttConnectionManager: MqttConnectionManager

    /**
     * Refresh subscriptions for a specific connector after it is created or updated.
     */
    fun refreshConnector(connectorId: UUID) {
        LOG.infof("Refreshing MQTT subscriptions for connector %s", connectorId)
        mqttConnectionManager.refreshConnector(connectorId)
    }

    /**
     * Remove subscriptions for a connector after it is deleted or deactivated.
     */
    fun disconnectConnector(connectorId: UUID) {
        LOG.infof("Disconnecting MQTT client for connector %s", connectorId)
        mqttConnectionManager.disconnectConnector(connectorId)
    }

    /**
     * Rebuild all routing tables and subscriptions.
     * Called after pipeline activate/deactivate/delete.
     */
    fun refreshAll() {
        LOG.info("Refreshing all MQTT subscriptions")
        mqttConnectionManager.refreshAll()
    }
}
