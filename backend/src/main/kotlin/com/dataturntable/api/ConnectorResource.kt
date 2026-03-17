package com.dataturntable.api

import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.Connector.Direction
import com.dataturntable.connectors.Connector.ConnectorType
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import com.dataturntable.auth.User
import com.dataturntable.security.SecurityUtils
import com.dataturntable.security.EncryptionUtils
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@Path("/connectors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class ConnectorResource {
    
    @Inject
    lateinit var jwt: JsonWebToken

    @PersistenceContext
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.mqtt.integrated.internal-broker-url", defaultValue = "mqtt://mosquitto:1883")
    lateinit var mqttInternalBrokerUrl: String

    @ConfigProperty(name = "dataturntable.mqtt.integrated.external-broker-url", defaultValue = "mqtt://localhost:1883")
    lateinit var mqttExternalBrokerUrl: String

    @ConfigProperty(name = "dataturntable.mqtt.integrated.websocket-url", defaultValue = "ws://localhost:9001")
    lateinit var mqttWebsocketUrl: String

    @ConfigProperty(name = "dataturntable.mqtt.integrated.default-topic", defaultValue = "dataturntable/events")
    lateinit var mqttDefaultTopic: String
    
    private val objectMapper = ObjectMapper()

    private fun getCurrentUser(): User? {
        val subject = jwt.subject ?: return null
        val userId = try { UUID.fromString(subject) } catch (_: Exception) { return null }
        return em.find(User::class.java, userId)
    }
    
    private fun getTenantIdFromToken(): String? {
        val tid = jwt.getClaim("tenantId") as? String
        return if (tid.isNullOrBlank()) null else tid
    }

    private fun maskConnectorConfig(rawConfig: Map<*, *>): Map<String, Any?> {
        val sensitiveFragments = listOf("password", "secret", "token", "apikey", "api_key", "key")
        val result = mutableMapOf<String, Any?>()
        rawConfig.forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            val lowerKey = key.lowercase()
            val isSensitive = sensitiveFragments.any { lowerKey.contains(it) }
            result[key] = when {
                isSensitive && v is String && v.isNotEmpty() -> "********"
                isSensitive && v != null -> "********"
                v is Map<*, *> -> maskConnectorConfig(v)
                v is List<*> -> v.map { item ->
                    when (item) {
                        is Map<*, *> -> maskConnectorConfig(item)
                        else -> item
                    }
                }
                else -> v
            }
        }
        return result
    }

    private fun parseConnectorType(type: String): ConnectorType? {
        return try {
            ConnectorType.valueOf(type.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseDirection(direction: String): Direction? {
        return try {
            Direction.valueOf(direction.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseUuid(value: String): UUID? {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun canReadConnector(currentUser: User?, tenantId: String): Boolean {
        if (currentUser == null) return false
        if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) return false
        return SecurityUtils.hasAbility(currentUser, "read:connector")
    }

    private fun canWriteConnector(currentUser: User?, tenantId: String): Boolean {
        if (currentUser == null) return false
        if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) return false
        return SecurityUtils.hasAbility(currentUser, "write:connector")
    }

    private fun connectorToMap(connector: Connector): Map<String, Any?> {
        val decrypted = EncryptionUtils.decrypt(connector.config ?: "{}")
        val configValue = try {
            val parsed = objectMapper.readValue(decrypted, Map::class.java)
            maskConnectorConfig(parsed)
        } catch (_: Exception) {
            emptyMap<String, Any>()
        }
        return mapOf(
            "id" to connector.id,
            "name" to connector.name,
            "connectorType" to connector.connectorType?.name,
            "direction" to connector.direction?.name,
            "config" to configValue,
            "description" to connector.description,
            "isActive" to connector.isActive,
            "createdAt" to connector.createdAt?.toString()
        )
    }
    
    @GET
    @Path("/mqtt/defaults")
    fun mqttDefaults(): Response {
        val currentUser = getCurrentUser() ?: return Response.status(401).entity(mapOf("error" to "Unauthorized")).build()
        if (!SecurityUtils.hasAbility(currentUser, "read:connector")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        return Response.ok(
            mapOf(
                "internalBrokerUrl" to mqttInternalBrokerUrl,
                "externalBrokerUrl" to mqttExternalBrokerUrl,
                "websocketUrl" to mqttWebsocketUrl,
                "defaultTopic" to mqttDefaultTopic,
                "integratedBrokerLabel" to "Integrated Mosquitto"
            )
        ).build()
    }

    @GET
    fun list(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("type") type: String?,
        @QueryParam("direction") direction: String?
    ): Response {
        val tid = tenantId ?: getTenantIdFromToken() ?: return Response.status(400)
            .entity(mapOf("error" to "tenantId is required"))
            .build()
        val tenantUuid = parseUuid(tid)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()

        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tid)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "read:connector")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        
        if (em.find(Tenant::class.java, tenantUuid) == null) {
            return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        }

        val parsedType = type?.let { parseConnectorType(it) }
        if (type != null && parsedType == null) {
            return Response.status(400).entity(mapOf("error" to "Invalid connector type")).build()
        }
        val parsedDirection = direction?.let { parseDirection(it) }
        if (direction != null && parsedDirection == null) {
            return Response.status(400).entity(mapOf("error" to "Invalid direction")).build()
        }

        val connectors = when {
            parsedType != null -> em.createQuery(
                "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.connectorType = :type",
                Connector::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .setParameter("type", parsedType)
                .resultList

            parsedDirection != null -> em.createQuery(
                "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.direction = :direction",
                Connector::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .setParameter("direction", parsedDirection)
                .resultList

            else -> em.createQuery(
                "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId",
                Connector::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .resultList
        }
        
        return Response.ok(connectors.map { connectorToMap(it) }).build()
    }
    
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String) ?: getTenantIdFromToken()
        val name = request["name"] as? String ?: return Response.status(400)
            .entity(mapOf("error" to "Name is required"))
            .build()
        val connectorType = request["connectorType"] as? String ?: return Response.status(400)
            .entity(mapOf("error" to "Connector type is required"))
            .build()
        val direction = request["direction"] as? String ?: return Response.status(400)
            .entity(mapOf("error" to "Direction is required"))
            .build()

        val parsedConnectorType = parseConnectorType(connectorType)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector type")).build()
        val parsedDirection = parseDirection(direction)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid direction")).build()
        
        if (tenantId == null) {
            return Response.status(400)
                .entity(mapOf("error" to "Tenant ID is required"))
                .build()
        }

        val tenantUuid = parseUuid(tenantId)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()

        val tenant = em.find(Tenant::class.java, tenantUuid)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val configValue = request["config"] ?: emptyMap<String, Any>()
        val configPlainJson = try {
            objectMapper.writeValueAsString(configValue)
        } catch (_: Exception) {
            "{}"
        }

        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "write:connector")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }

        val connector = Connector().apply {
            this.name = name
            this.tenant = tenant
            this.connectorType = parsedConnectorType
            this.direction = parsedDirection
            this.config = EncryptionUtils.encrypt(configPlainJson)
            this.description = request["description"] as? String
            this.isActive = true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        connector.persist()
        
        return Response.status(201).entity(mapOf(
            "id" to connector.id,
            "name" to connector.name,
            "connectorType" to connector.connectorType?.name,
            "direction" to connector.direction?.name
        )).build()
    }
    
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        val connectorId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector id")).build()
        val connector = em.find(Connector::class.java, connectorId)
        val tenantId = getTenantIdFromToken()
        return if (connector != null) {
            val currentUser = getCurrentUser()
            if (tenantId != null && connector.tenant?.id?.toString() != tenantId) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, connector.tenant?.id.toString())) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "read:connector")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            Response.ok(connectorToMap(connector)).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        }
    }

    @POST
    @Path("/{id}/connect")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    fun connect(@PathParam("id") id: String): Response {
        val connectorId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector id")).build()
        val connector = em.find(Connector::class.java, connectorId)
            ?: return Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        val tenantId = connector.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Connector tenant not found")).build()
        val currentUser = getCurrentUser()
        if (!canWriteConnector(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        connector.isActive = true
        connector.updatedAt = java.time.Instant.now()
        connector.persist()
        return Response.ok(connectorToMap(connector) + mapOf("message" to "Connector connected")).build()
    }

    @POST
    @Path("/{id}/disconnect")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    fun disconnect(@PathParam("id") id: String): Response {
        val connectorId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector id")).build()
        val connector = em.find(Connector::class.java, connectorId)
            ?: return Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        val tenantId = connector.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Connector tenant not found")).build()
        val currentUser = getCurrentUser()
        if (!canWriteConnector(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        connector.isActive = false
        connector.updatedAt = java.time.Instant.now()
        connector.persist()
        return Response.ok(connectorToMap(connector) + mapOf("message" to "Connector disconnected")).build()
    }

    @POST
    @Path("/{id}/test")
    @Consumes(MediaType.WILDCARD)
    fun testConnection(@PathParam("id") id: String): Response {
        val connectorId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector id")).build()
        val connector = em.find(Connector::class.java, connectorId)
            ?: return Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        val tenantId = connector.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Connector tenant not found")).build()
        val currentUser = getCurrentUser()
        if (!canReadConnector(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val decrypted = EncryptionUtils.decrypt(connector.config ?: "{}")
        val configValue = try {
            objectMapper.readValue(decrypted, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
        } catch (_: Exception) {
            return Response.status(400).entity(mapOf("error" to "Connector config is not valid JSON")).build()
        }

        val requiredKeys = when (connector.connectorType) {
            ConnectorType.MQTT -> listOf("broker", "topic")
            ConnectorType.REST -> listOf("baseUrl")
            ConnectorType.WEBSOCKET -> listOf("url")
            ConnectorType.UDP -> listOf("host", "port")
            ConnectorType.FILE -> listOf("path")
            ConnectorType.CUSTOM -> emptyList()
            null -> emptyList()
        }
        val missing = requiredKeys.filter { key ->
            val value = configValue[key]
            value == null || (value is String && value.isBlank())
        }
        if (missing.isNotEmpty()) {
            return Response.status(400).entity(
                mapOf("error" to "Connector config missing required fields", "missing" to missing)
            ).build()
        }

        return Response.ok(mapOf(
            "ok" to true,
            "message" to "Connector configuration validated",
            "connectorId" to connector.id,
            "connectorType" to connector.connectorType?.name,
            "direction" to connector.direction?.name,
            "isActive" to connector.isActive
        )).build()
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val connectorId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector id")).build()
        val connector = em.find(Connector::class.java, connectorId)
        val tenantId = getTenantIdFromToken()
        return if (connector != null) {
            val currentUser = getCurrentUser()
            if (tenantId != null && connector.tenant?.id?.toString() != tenantId) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, connector.tenant?.id.toString())) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "write:connector")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            request["name"]?.let { connector.name = it as String }
            request["connectorType"]?.let {
                val parsed = parseConnectorType(it as String)
                    ?: return Response.status(400).entity(mapOf("error" to "Invalid connector type")).build()
                connector.connectorType = parsed
            }
            request["direction"]?.let {
                val parsed = parseDirection(it as String)
                    ?: return Response.status(400).entity(mapOf("error" to "Invalid direction")).build()
                connector.direction = parsed
            }
            request["config"]?.let {
                if (it is Map<*, *> && it.isEmpty()) {
                    return@let
                }
                val plain = try { objectMapper.writeValueAsString(it) } catch (_: Exception) { it.toString() }
                connector.config = EncryptionUtils.encrypt(plain)
            }
            request["description"]?.let { connector.description = it as String }
            request["isActive"]?.let { connector.isActive = it as Boolean }
            connector.updatedAt = java.time.Instant.now()
            connector.persist()
            
            Response.ok(mapOf(
                "id" to connector.id,
                "name" to connector.name,
                "connectorType" to connector.connectorType?.name,
                "direction" to connector.direction?.name,
                "isActive" to connector.isActive
            )).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    fun delete(@PathParam("id") id: String): Response {
        val connectorId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid connector id")).build()
        val connector = em.find(Connector::class.java, connectorId)
        val tenantId = getTenantIdFromToken()
        return if (connector != null) {
            val currentUser = getCurrentUser()
            if (tenantId != null && connector.tenant?.id?.toString() != tenantId) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, connector.tenant?.id.toString())) {
                return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
            }
            if (!SecurityUtils.hasAbility(currentUser, "delete:connector")) {
                return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
            }
            connector.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        }
    }
}
