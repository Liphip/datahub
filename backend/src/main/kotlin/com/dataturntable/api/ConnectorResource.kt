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
                else -> v
            }
        }
        return result
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

        val currentUser = getCurrentUser()
        if (currentUser == null || !SecurityUtils.canAccessTenant(currentUser, tid)) {
            return Response.status(403).entity(mapOf("error" to "Forbidden")).build()
        }
        if (!SecurityUtils.hasAbility(currentUser, "read:connector")) {
            return Response.status(403).entity(mapOf("error" to "Insufficient permissions")).build()
        }
        
        val tenant = em.find(Tenant::class.java, UUID.fromString(tid))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val tenantUuid = UUID.fromString(tid)
        val connectors = when {
            type != null -> em.createQuery(
                "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.connectorType = :type",
                Connector::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .setParameter("type", ConnectorType.valueOf(type.uppercase()))
                .resultList

            direction != null -> em.createQuery(
                "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId AND c.direction = :direction",
                Connector::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .setParameter("direction", Direction.valueOf(direction.uppercase()))
                .resultList

            else -> em.createQuery(
                "SELECT c FROM Connector c WHERE c.tenant.id = :tenantId",
                Connector::class.java
            )
                .setParameter("tenantId", tenantUuid)
                .resultList
        }
        
        return Response.ok(connectors.map { connector ->
            val decrypted = EncryptionUtils.decrypt(connector.config ?: "{}")
            val configValue = try {
                val parsed = objectMapper.readValue(decrypted, Map::class.java)
                maskConnectorConfig(parsed)
            } catch (_: Exception) {
                emptyMap<String, Any>()
            }
            mapOf(
                "id" to connector.id,
                "name" to connector.name,
                "connectorType" to connector.connectorType?.name,
                "direction" to connector.direction?.name,
                "config" to configValue,
                "description" to connector.description,
                "isActive" to connector.isActive,
                "createdAt" to connector.createdAt?.toString()
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
        
        if (tenantId == null) {
            return Response.status(400)
                .entity(mapOf("error" to "Tenant ID is required"))
                .build()
        }
        
        val tenant = em.find(Tenant::class.java, UUID.fromString(tenantId))
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
            this.connectorType = ConnectorType.valueOf(connectorType.uppercase())
            this.direction = Direction.valueOf(direction.uppercase())
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
        val connector = em.find(Connector::class.java, UUID.fromString(id))
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
            val decrypted = EncryptionUtils.decrypt(connector.config ?: "{}")
            val configValue = try {
                val parsed = objectMapper.readValue(decrypted, Map::class.java)
                maskConnectorConfig(parsed)
            } catch (_: Exception) {
                emptyMap<String, Any>()
            }
            Response.ok(mapOf(
                "id" to connector.id,
                "name" to connector.name,
                "connectorType" to connector.connectorType?.name,
                "direction" to connector.direction?.name,
                "config" to configValue,
                "description" to connector.description,
                "isActive" to connector.isActive,
                "createdAt" to connector.createdAt?.toString()
            )).build()
        } else {
            Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        }
    }
    
    @PATCH
    @Path("/{id}")
    @Transactional
    fun update(@PathParam("id") id: String, request: Map<String, Any>): Response {
        val connector = em.find(Connector::class.java, UUID.fromString(id))
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
            request["connectorType"]?.let { connector.connectorType = ConnectorType.valueOf((it as String).uppercase()) }
            request["direction"]?.let { connector.direction = Direction.valueOf((it as String).uppercase()) }
            request["config"]?.let {
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
        val connector = em.find(Connector::class.java, UUID.fromString(id))
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
