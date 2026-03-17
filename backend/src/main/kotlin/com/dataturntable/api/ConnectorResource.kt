package com.dataturntable.api

import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.Connector.Direction
import com.dataturntable.connectors.Connector.ConnectorType
import com.dataturntable.connectors.BrokerCredential
import com.dataturntable.pipeline.Pipeline
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

    private fun connectorToMap(connector: Connector, pipelineReferenceCount: Int? = null): Map<String, Any?> {
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
            "pipelineReferenceCount" to pipelineReferenceCount,
            "createdAt" to connector.createdAt?.toString()
        )
    }

    private fun canManageBrokerCredentials(currentUser: User?, tenantId: String): Boolean {
        if (currentUser == null) return false
        if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) return false
        return currentUser.isSuperAdmin() || SecurityUtils.hasAbility(currentUser, "manage:tenant")
    }

    private fun canReadBrokerCredentials(currentUser: User?, tenantId: String): Boolean {
        if (currentUser == null) return false
        if (!SecurityUtils.canAccessTenant(currentUser, tenantId)) return false
        return SecurityUtils.hasAbility(currentUser, "read:connector")
    }

    private fun mqttTopicMatches(pattern: String, topic: String): Boolean {
        val regex = pattern
            .replace("+", "[^/]+")
            .replace("#", ".*")
        return Regex("^$regex$").matches(topic)
    }

    private fun validateMqttCredentialBinding(
        tenantUuid: UUID,
        configMap: Map<*, *>
    ): Response? {
        val credentialIdRaw = configMap["brokerCredentialId"]?.toString()?.trim()
        if (credentialIdRaw.isNullOrBlank()) return null
        val credentialId = parseUuid(credentialIdRaw)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid brokerCredentialId")).build()
        val credential = em.find(BrokerCredential::class.java, credentialId)
            ?: return Response.status(400).entity(mapOf("error" to "Broker credential not found")).build()
        if (credential.tenant?.id != tenantUuid) {
            return Response.status(403).entity(mapOf("error" to "Broker credential does not belong to tenant")).build()
        }
        if (!credential.isActive) {
            return Response.status(400).entity(mapOf("error" to "Broker credential is inactive")).build()
        }

        val topic = configMap["topic"]?.toString()?.trim()
        if (!topic.isNullOrBlank()) {
            val allowedTopics = try {
                objectMapper.readValue(credential.allowedTopics, List::class.java).map { it.toString() }
            } catch (_: Exception) {
                emptyList<String>()
            }
            if (allowedTopics.isNotEmpty() && allowedTopics.none { mqttTopicMatches(it, topic) }) {
                return Response.status(400).entity(
                    mapOf(
                        "error" to "MQTT topic is not allowed by broker credential",
                        "allowedTopics" to allowedTopics
                    )
                ).build()
            }
        }
        return null
    }

    private fun sanitizeMqttConfig(configMap: Map<*, *>): Map<String, Any?> {
        val normalized = configMap.entries.associate { (k, v) ->
            (k?.toString() ?: "") to v
        }.toMutableMap()
        val credentialId = normalized["brokerCredentialId"]?.toString()?.trim()
        if (!credentialId.isNullOrBlank()) {
            normalized.remove("username")
            normalized.remove("password")
        }
        return normalized.filterKeys { it.isNotBlank() }
    }

    private fun extractBrokerCredentialId(connector: Connector): UUID? {
        val configRaw = try {
            EncryptionUtils.decrypt(connector.config ?: "{}")
        } catch (_: Exception) {
            "{}"
        }
        val configMap = try {
            objectMapper.readValue(configRaw, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
        } catch (_: Exception) {
            emptyMap<String, Any>()
        }
        val configuredId = configMap["brokerCredentialId"]?.toString()?.trim()
        return configuredId?.let { parseUuid(it) }
    }

    private fun connectorUsesBrokerCredential(connector: Connector, credentialId: UUID): Boolean {
        return extractBrokerCredentialId(connector) == credentialId
    }

    private fun brokerCredentialReferenceCounts(tenantUuid: UUID): Map<UUID, Int> {
        val mqttConnectors = em.createQuery(
            "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.connectorType = :connectorType",
            Connector::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setParameter("connectorType", ConnectorType.MQTT)
            .resultList

        val counts = mutableMapOf<UUID, Int>()
        mqttConnectors.forEach { connector ->
            val credentialId = extractBrokerCredentialId(connector) ?: return@forEach
            counts[credentialId] = (counts[credentialId] ?: 0) + 1
        }
        return counts
    }

    private fun connectorReferenceCounts(tenantUuid: UUID): Map<UUID, Int> {
        val pipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId",
            Pipeline::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList

        val counts = mutableMapOf<UUID, Int>()
        pipelines.forEach { pipeline ->
            val graphDefinition = pipeline.graphDefinition ?: return@forEach
            val graphMap = try {
                objectMapper.readValue(graphDefinition, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
            } catch (_: Exception) {
                emptyMap<String, Any>()
            }
            val nodes = graphMap["nodes"] as? List<*> ?: emptyList<Any>()
            nodes.forEach { node ->
                val nodeMap = node as? Map<*, *> ?: return@forEach
                val connectorId = nodeMap["connectorId"]?.toString()?.trim()?.let { parseUuid(it) } ?: return@forEach
                counts[connectorId] = (counts[connectorId] ?: 0) + 1
            }
        }
        return counts
    }

    private fun connectorReferenceSummaries(tenantUuid: UUID, connectorId: UUID): List<Map<String, Any?>> {
        val pipelines = em.createQuery(
            "SELECT p FROM Pipeline p WHERE p.tenant.id = :tenantId",
            Pipeline::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList

        val references = mutableListOf<Map<String, Any?>>()
        pipelines.forEach { pipeline ->
            val graphDefinition = pipeline.graphDefinition ?: return@forEach
            val graphMap = try {
                objectMapper.readValue(graphDefinition, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
            } catch (_: Exception) {
                emptyMap<String, Any>()
            }
            val nodes = graphMap["nodes"] as? List<*> ?: emptyList<Any>()
            nodes.forEach { node ->
                val nodeMap = node as? Map<*, *> ?: return@forEach
                val nodeConnectorId = nodeMap["connectorId"]?.toString()?.trim()?.let { parseUuid(it) } ?: return@forEach
                if (nodeConnectorId != connectorId) return@forEach
                references.add(
                    mapOf(
                        "pipelineId" to pipeline.id,
                        "pipelineName" to pipeline.name,
                        "pipelineIsActive" to pipeline.isActive,
                        "nodeId" to nodeMap["id"],
                        "nodeType" to nodeMap["type"]
                    )
                )
            }
        }
        return references
    }

    private fun brokerCredentialToMap(credential: BrokerCredential): Map<String, Any?> {
        val topics = try {
            objectMapper.readValue(credential.allowedTopics, List::class.java)
        } catch (_: Exception) {
            emptyList<String>()
        }
        return mapOf(
            "id" to credential.id,
            "tenantId" to credential.tenant?.id,
            "name" to credential.name,
            "username" to credential.username,
            "allowedTopics" to topics,
            "isActive" to credential.isActive,
            "createdAt" to credential.createdAt?.toString(),
            "updatedAt" to credential.updatedAt?.toString()
        )
    }

    private fun normalizeTopics(raw: Any?): List<String> {
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }.distinct()
            is String -> raw.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }.distinct()
            else -> emptyList()
        }
    }

    private fun generateCredentialPassword(length: Int = 24): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%&*"
        val random = java.security.SecureRandom()
        return (1..length)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
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
    @Path("/mqtt/credentials")
    fun listMqttCredentials(@QueryParam("tenantId") tenantId: String?): Response {
        val tid = tenantId ?: getTenantIdFromToken()
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val tenantUuid = parseUuid(tid)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
        val currentUser = getCurrentUser()
        if (!canReadBrokerCredentials(currentUser, tid)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val credentials = em.createQuery(
            "SELECT b FROM BrokerCredential b WHERE b.tenant.id = :tenantId ORDER BY b.createdAt DESC",
            BrokerCredential::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .resultList
        val referenceCounts = brokerCredentialReferenceCounts(tenantUuid)

        return Response.ok(credentials.map { credential ->
            val credentialId = credential.id
            brokerCredentialToMap(credential) + mapOf(
                "referenceCount" to (if (credentialId != null) (referenceCounts[credentialId] ?: 0) else 0)
            )
        }).build()
    }

    @POST
    @Path("/mqtt/credentials")
    @Transactional
    fun createMqttCredential(@QueryParam("tenantId") tenantId: String?, request: Map<String, Any>): Response {
        val tid = tenantId ?: (request["tenantId"] as? String) ?: getTenantIdFromToken()
            ?: return Response.status(400).entity(mapOf("error" to "tenantId is required")).build()
        val tenantUuid = parseUuid(tid)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid tenantId")).build()
        val currentUser = getCurrentUser()
        if (!canManageBrokerCredentials(currentUser, tid)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val tenant = em.find(Tenant::class.java, tenantUuid)
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()

        val name = (request["name"] as? String)?.trim()
            ?: return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        if (name.isBlank()) {
            return Response.status(400).entity(mapOf("error" to "Name is required")).build()
        }
        val username = ((request["username"] as? String)?.trim()).takeUnless { it.isNullOrBlank() }
            ?: "mqtt_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}_${UUID.randomUUID().toString().take(6)}"
        val password = ((request["password"] as? String)?.trim()).takeUnless { it.isNullOrBlank() }
            ?: generateCredentialPassword()
        val allowedTopics = normalizeTopics(request["allowedTopics"]).ifEmpty {
            listOf((request["defaultTopic"] as? String)?.trim()).filterNotNull().filter { it.isNotBlank() }
        }
        if (allowedTopics.isEmpty()) {
            return Response.status(400).entity(mapOf("error" to "allowedTopics must include at least one topic")).build()
        }

        val exists = em.createQuery(
            "SELECT COUNT(b) FROM BrokerCredential b WHERE b.tenant.id = :tenantId AND (b.name = :name OR b.username = :username)",
            java.lang.Long::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setParameter("name", name)
            .setParameter("username", username)
            .singleResult
        if (exists > 0L) {
            return Response.status(409).entity(mapOf("error" to "Credential name or username already exists in tenant")).build()
        }

        val credential = BrokerCredential().apply {
            this.tenant = tenant
            this.name = name
            this.username = username
            this.passwordEncrypted = EncryptionUtils.encrypt(password)
            this.allowedTopics = objectMapper.writeValueAsString(allowedTopics)
            this.isActive = request["isActive"] as? Boolean ?: true
            this.createdAt = java.time.Instant.now()
            this.updatedAt = java.time.Instant.now()
        }
        credential.persist()

        return Response.status(201).entity(
            brokerCredentialToMap(credential) + mapOf("password" to password)
        ).build()
    }

    @PATCH
    @Path("/mqtt/credentials/{id}")
    @Transactional
    fun updateMqttCredential(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val credentialId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid credential id")).build()
        val credential = em.find(BrokerCredential::class.java, credentialId)
            ?: return Response.status(404).entity(mapOf("error" to "Credential not found")).build()
        val tenantId = credential.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Credential tenant not found")).build()
        val currentUser = getCurrentUser()
        if (!canManageBrokerCredentials(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        (request["name"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { credential.name = it }
        request["allowedTopics"]?.let {
            val topics = normalizeTopics(it)
            if (topics.isNotEmpty()) {
                credential.allowedTopics = objectMapper.writeValueAsString(topics)
            }
        }
        (request["isActive"] as? Boolean)?.let { credential.isActive = it }
        credential.updatedAt = java.time.Instant.now()
        credential.persist()

        return Response.ok(brokerCredentialToMap(credential)).build()
    }

    @POST
    @Path("/mqtt/credentials/{id}/rotate-password")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    fun rotateMqttCredentialPassword(@PathParam("id") id: String, request: Map<String, Any>?): Response {
        val credentialId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid credential id")).build()
        val credential = em.find(BrokerCredential::class.java, credentialId)
            ?: return Response.status(404).entity(mapOf("error" to "Credential not found")).build()
        val tenantId = credential.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Credential tenant not found")).build()
        val currentUser = getCurrentUser()
        if (!canManageBrokerCredentials(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val password = (request?.get("password") as? String)?.trim().takeUnless { it.isNullOrBlank() }
            ?: generateCredentialPassword()
        credential.passwordEncrypted = EncryptionUtils.encrypt(password)
        credential.updatedAt = java.time.Instant.now()
        credential.persist()

        return Response.ok(brokerCredentialToMap(credential) + mapOf("password" to password)).build()
    }

    @DELETE
    @Path("/mqtt/credentials/{id}")
    @Transactional
    fun deleteMqttCredential(@PathParam("id") id: String): Response {
        val credentialId = parseUuid(id)
            ?: return Response.status(400).entity(mapOf("error" to "Invalid credential id")).build()
        val credential = em.find(BrokerCredential::class.java, credentialId)
            ?: return Response.status(404).entity(mapOf("error" to "Credential not found")).build()
        val tenantId = credential.tenant?.id?.toString()
            ?: return Response.status(400).entity(mapOf("error" to "Credential tenant not found")).build()
        val currentUser = getCurrentUser()
        if (!canManageBrokerCredentials(currentUser, tenantId)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }

        val tenantUuid = credential.tenant?.id
            ?: return Response.status(400).entity(mapOf("error" to "Credential tenant not found")).build()
        val mqttConnectors = em.createQuery(
            "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.connectorType = :connectorType",
            Connector::class.java
        )
            .setParameter("tenantId", tenantUuid)
            .setParameter("connectorType", ConnectorType.MQTT)
            .resultList

        val referencing = mqttConnectors.filter { connectorUsesBrokerCredential(it, credentialId) }
        if (referencing.isNotEmpty()) {
            return Response.status(409).entity(
                mapOf(
                    "error" to "Broker credential is in use by MQTT connectors",
                    "references" to referencing.take(20).map {
                        mapOf("id" to it.id, "name" to it.name)
                    },
                    "referenceCount" to referencing.size
                )
            ).build()
        }

        credential.delete()
        return Response.noContent().build()
    }

    @GET
    fun list(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("type") type: String?,
        @QueryParam("direction") direction: String?,
        @QueryParam("q") query: String?,
        @QueryParam("isActive") isActive: Boolean?
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

        val normalizedQuery = query?.trim()?.lowercase()
        val jpql = StringBuilder("SELECT c FROM Connector c WHERE c.tenant.id = :tenantId")
        if (parsedType != null) jpql.append(" AND c.connectorType = :type")
        if (parsedDirection != null) jpql.append(" AND c.direction = :direction")
        if (isActive != null) jpql.append(" AND c.isActive = :isActive")
        if (!normalizedQuery.isNullOrBlank()) {
            jpql.append(" AND (LOWER(c.name) LIKE :q OR LOWER(COALESCE(c.description, '')) LIKE :q)")
        }
        jpql.append(" ORDER BY c.createdAt DESC")

        val connectorQuery = em.createQuery(jpql.toString(), Connector::class.java)
            .setParameter("tenantId", tenantUuid)
        if (parsedType != null) connectorQuery.setParameter("type", parsedType)
        if (parsedDirection != null) connectorQuery.setParameter("direction", parsedDirection)
        if (isActive != null) connectorQuery.setParameter("isActive", isActive)
        if (!normalizedQuery.isNullOrBlank()) connectorQuery.setParameter("q", "%$normalizedQuery%")

        val connectors = connectorQuery.resultList
        
        val referenceCounts = connectorReferenceCounts(tenantUuid)
        return Response.ok(connectors.map { connector ->
            val connectorId = connector.id
            connectorToMap(
                connector,
                if (connectorId != null) (referenceCounts[connectorId] ?: 0) else 0
            )
        }).build()
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
        val storedConfigValue: Any = if (parsedConnectorType == ConnectorType.MQTT && configValue is Map<*, *>) {
            val sanitized = sanitizeMqttConfig(configValue)
            validateMqttCredentialBinding(tenantUuid, sanitized)?.let { return it }
            sanitized
        } else {
            configValue
        }
        val configPlainJson = try {
            objectMapper.writeValueAsString(storedConfigValue)
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

    @GET
    @Path("/{id}/references")
    fun getReferences(@PathParam("id") id: String): Response {
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

        val references = connector.tenant?.id?.let { connectorReferenceSummaries(it, connectorId) } ?: emptyList()
        return Response.ok(
            mapOf(
                "connectorId" to connector.id,
                "connectorName" to connector.name,
                "referenceCount" to references.size,
                "references" to references
            )
        ).build()
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
                val effectiveConfigValue: Any = if (connector.connectorType == ConnectorType.MQTT && it is Map<*, *>) {
                    val tenantUuid = connector.tenant?.id
                        ?: return Response.status(400).entity(mapOf("error" to "Connector tenant not found")).build()
                    val sanitized = sanitizeMqttConfig(it)
                    validateMqttCredentialBinding(tenantUuid, sanitized)?.let { response -> return response }
                    sanitized
                } else {
                    it
                }
                val plain = try { objectMapper.writeValueAsString(effectiveConfigValue) } catch (_: Exception) { effectiveConfigValue.toString() }
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
            val connectorTenantId = connector.tenant?.id
                ?: return Response.status(400).entity(mapOf("error" to "Connector tenant not found")).build()
            val references = connectorReferenceSummaries(connectorTenantId, connectorId)
            if (references.isNotEmpty()) {
                return Response.status(409).entity(
                    mapOf(
                        "error" to "Connector is used by pipeline graphs",
                        "referenceCount" to references.size,
                        "references" to references.take(20)
                    )
                ).build()
            }
            connector.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        }
    }
}
