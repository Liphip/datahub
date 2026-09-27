package com.dataturntable.connectors

import com.dataturntable.secrets.SecretService
import com.dataturntable.connectors.mqtt.MqttMessageRouter
import com.dataturntable.connectors.websocket.WebSocketMessageRouter
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class ConnectorSecretReferenceTest {
    @Inject lateinit var runtime: ConnectorRuntimeService
    @Inject lateinit var mqttRouter: MqttMessageRouter
    @Inject lateinit var webSocketRouter: WebSocketMessageRouter
    @Inject lateinit var secrets: SecretService
    @Inject lateinit var em: EntityManager

    @Test
    @Transactional
    fun `connector runtime resolves references without retaining them in runtime config`() {
        val tenant = Tenant().apply { name = "runtime secret tenant"; createdAt = Instant.now(); updatedAt = Instant.now() }
        em.persist(tenant)
        em.flush()
        val reference = secrets.create(tenant.id!!, "connector.api-key", "runtime-only-key")
        val connector = Connector().apply {
            this.tenant = tenant
            name = "secret-backed rest"
            connectorType = Connector.ConnectorType.REST
            direction = Connector.Direction.OUTPUT
            config = """{"baseUrl":"https://api.example.com","secretRefs":{"apiKey":{"id":"${reference.id}","version":${reference.version}}}}"""
        }

        val resolved = runtime.decryptConfig(connector)
        assertEquals("runtime-only-key", resolved["apiKey"])
        assertFalse(resolved.containsKey("secretRefs"))
    }

    @Test
    @Transactional
    fun `persistent MQTT and WebSocket loaders resolve and strip secret references`() {
        val tenant = Tenant().apply { name = "persistent secret tenant"; createdAt = Instant.now(); updatedAt = Instant.now() }
        em.persist(tenant)
        em.flush()
        val reference = secrets.create(tenant.id!!, "connector.auth-token", "runtime-only-token")
        val refs = """{"authToken":{"id":"${reference.id}","version":${reference.version}}}"""
        val mqtt = Connector().apply {
            this.tenant = tenant
            name = "secret-backed mqtt"
            connectorType = Connector.ConnectorType.MQTT
            direction = Connector.Direction.INPUT
            config = """{"brokerUrl":"tcp://broker.example.test:1883","secretRefs":$refs}"""
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        val websocket = Connector().apply {
            this.tenant = tenant
            name = "secret-backed websocket"
            connectorType = Connector.ConnectorType.WEBSOCKET
            direction = Connector.Direction.INPUT
            config = """{"url":"wss://socket.example.test","secretRefs":$refs}"""
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        em.persist(mqtt)
        em.persist(websocket)
        em.flush()

        val mqttConfig = mqttRouter.resolveConnectorConfig(mqtt.id!!)?.second!!
        val webSocketConfig = webSocketRouter.resolveConnectorConfig(websocket.id!!)?.second!!

        listOf(mqttConfig, webSocketConfig).forEach { config ->
            assertEquals("runtime-only-token", config["authToken"])
            assertFalse(config.containsKey("secretRefs"))
        }
    }
}
