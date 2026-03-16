package com.dataturntable.api

import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.Connector.Direction
import com.dataturntable.connectors.Connector.ConnectorType
import com.dataturntable.tenant.Tenant
import io.quarkus.security.Authenticated
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/connectors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class ConnectorResource {
    
    @GET
    fun list(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("type") type: String?,
        @QueryParam("direction") direction: String?
    ): Response {
        val tid = tenantId ?: return Response.status(400)
            .entity(mapOf("error" to "tenantId is required"))
            .build()
        
        val tenant = Tenant.findById(UUID.fromString(tid))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val connectors = when {
            type != null -> Connector.findByTenantAndType(UUID.fromString(tid), ConnectorType.valueOf(type.uppercase()))
            direction != null -> Connector.findByTenantAndDirection(UUID.fromString(tid), Direction.valueOf(direction.uppercase()))
            else -> Connector.findByTenantId(UUID.fromString(tid))
        }
        
        return Response.ok(connectors.map { mapOf(
            "id" to it.id,
            "name" to it.name,
            "connectorType" to it.connectorType?.name,
            "direction" to it.direction?.name,
            "description" to it.description,
            "isActive" to it.isActive,
            "createdAt" to it.createdAt?.toString()
        ) }).build()
    }
    
    @POST
    @Transactional
    fun create(@QueryParam("tenantId") tenantIdQuery: String?, request: Map<String, Any>): Response {
        val tenantId = tenantIdQuery ?: (request["tenantId"] as? String)
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
        
        val tenant = Tenant.findById(UUID.fromString(tenantId))
            ?: return Response.status(400).entity(mapOf("error" to "Tenant not found")).build()
        
        val connector = Connector().apply {
            this.name = name
            this.tenant = tenant
            this.connectorType = ConnectorType.valueOf(connectorType.uppercase())
            this.direction = Direction.valueOf(direction.uppercase())
            this.config = (request["config"] ?: emptyMap<String, Any>()).toString()
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
        val connector = Connector.findById(UUID.fromString(id))
        return if (connector != null) {
            Response.ok(mapOf(
                "id" to connector.id,
                "name" to connector.name,
                "connectorType" to connector.connectorType?.name,
                "direction" to connector.direction?.name,
                "config" to connector.config,
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
        val connector = Connector.findById(UUID.fromString(id))
        return if (connector != null) {
            request["name"]?.let { connector.name = it as String }
            request["connectorType"]?.let { connector.connectorType = ConnectorType.valueOf((it as String).uppercase()) }
            request["direction"]?.let { connector.direction = Direction.valueOf((it as String).uppercase()) }
            request["config"]?.let { connector.config = it.toString() }
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
        val connector = Connector.findById(UUID.fromString(id))
        return if (connector != null) {
            connector.delete()
            Response.noContent().build()
        } else {
            Response.status(404).entity(mapOf("error" to "Connector not found")).build()
        }
    }
}
