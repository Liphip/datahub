package com.dataturntable.api

import com.dataturntable.auth.User
import com.dataturntable.metadata.MetadataAttributeVersion
import com.dataturntable.metadata.MetadataIdentifier
import com.dataturntable.metadata.MetadataNodes
import com.dataturntable.metadata.MetadataObject
import com.dataturntable.metadata.MetadataRelation
import com.dataturntable.metadata.MetadataService
import com.dataturntable.metadata.MetadataType
import com.dataturntable.metadata.MetadataValidationException
import com.dataturntable.metadata.ObjectRef
import com.dataturntable.metadata.RelationChange
import com.dataturntable.metadata.SaveMetadataConfig
import com.dataturntable.metadata.UpsertRequest
import com.dataturntable.metadata.parseInstant
import com.dataturntable.security.SecurityUtils
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant
import java.util.UUID

/**
 * Metadata of the things measurements come from: types, objects with identifiers from
 * source systems, versioned attributes and relations (`metadata:read` / `metadata:write`).
 */
@Path("/api/internal/v1/metadata")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
class MetadataResource {

    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var metadata: MetadataService

    @Inject
    lateinit var nodes: MetadataNodes

    // ── Access ───────────────────────────────────────────────────────────────

    private fun currentUser(): User? =
        jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { em.find(User::class.java, it) }

    private fun error(status: Int, message: String): Response = Response.status(status).entity(mapOf("error" to message)).build()

    /** Resolves the tenant and checks access; returns the tenant id or throws a WebApplicationException. */
    private fun authorize(tenantId: String?, ability: String): UUID {
        val user = currentUser() ?: throw WebApplicationException(error(401, "Unauthorized"))
        val id = tenantId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw WebApplicationException(error(400, "Invalid tenantId"))
        if (!SecurityUtils.canAccessTenant(user, id.toString())) throw WebApplicationException(error(403, "Forbidden"))
        if (!SecurityUtils.hasAbility(user, ability)) throw WebApplicationException(error(403, "Insufficient permissions"))
        return id
    }

    private fun <T> validated(block: () -> T): T = try {
        block()
    } catch (e: MetadataValidationException) {
        throw WebApplicationException(error(400, e.message ?: "Invalid metadata"))
    }

    private fun json(value: Any?): String = objectMapper.writeValueAsString(value ?: emptyList<Any>())

    // ── Types ────────────────────────────────────────────────────────────────

    private fun typeDto(t: MetadataType) = mapOf(
        "id" to t.id, "tenantId" to t.tenantId, "typeKey" to t.typeKey, "name" to t.name, "description" to t.description,
        "attributeSchema" to metadata.parseList(t.attributeSchema),
        "identifierSchemes" to metadata.parseList(t.identifierSchemes),
        "relationSchema" to metadata.parseList(t.relationSchema),
        "template" to t.template,
        "objectCount" to em.createQuery("SELECT COUNT(o) FROM MetadataObject o WHERE o.typeId = :t", java.lang.Long::class.java)
            .setParameter("t", t.id).singleResult,
    )

    @GET
    @Path("/types")
    fun listTypes(@QueryParam("tenantId") tenantId: String?): Response {
        val tid = authorize(tenantId, "metadata:read")
        return Response.ok(em.createQuery("SELECT t FROM MetadataType t WHERE t.tenantId = :t ORDER BY t.name", MetadataType::class.java)
            .setParameter("t", tid).resultList.map(::typeDto)).build()
    }

    @POST
    @Path("/types")
    @Transactional
    fun createType(@QueryParam("tenantId") tenantId: String?, body: Map<String, Any?>): Response {
        val tid = authorize(tenantId, "metadata:write")
        val key = body["typeKey"]?.toString()?.trim()?.takeIf { KEY.matches(it) }
            ?: return error(400, "typeKey must be lower case letters, digits and underscores")
        if (em.createQuery("SELECT COUNT(t) FROM MetadataType t WHERE t.tenantId = :t AND t.typeKey = :k", java.lang.Long::class.java)
                .setParameter("t", tid).setParameter("k", key).singleResult > 0) return error(409, "Type '$key' exists")
        val type = MetadataType().apply {
            this.tenantId = tid; typeKey = key
            name = body["name"]?.toString()?.ifBlank { null } ?: key
            description = body["description"]?.toString()
            attributeSchema = json(body["attributeSchema"]); identifierSchemes = json(body["identifierSchemes"]); relationSchema = json(body["relationSchema"])
        }
        em.persist(type)
        return Response.status(201).entity(typeDto(type)).build()
    }

    @PATCH
    @Path("/types/{id}")
    @Transactional
    fun updateType(@PathParam("id") id: String, body: Map<String, Any?>): Response {
        val type = runCatching { UUID.fromString(id) }.getOrNull()?.let { em.find(MetadataType::class.java, it) } ?: return error(404, "Type not found")
        authorize(type.tenantId.toString(), "metadata:write")
        body["name"]?.let { type.name = it.toString() }
        if (body.containsKey("description")) type.description = body["description"]?.toString()
        if (body.containsKey("attributeSchema")) type.attributeSchema = json(body["attributeSchema"])
        if (body.containsKey("identifierSchemes")) type.identifierSchemes = json(body["identifierSchemes"])
        if (body.containsKey("relationSchema")) type.relationSchema = json(body["relationSchema"])
        type.updatedAt = Instant.now()
        return Response.ok(typeDto(type)).build()
    }

    @DELETE
    @Path("/types/{id}")
    @Transactional
    fun deleteType(@PathParam("id") id: String): Response {
        val type = runCatching { UUID.fromString(id) }.getOrNull()?.let { em.find(MetadataType::class.java, it) } ?: return error(404, "Type not found")
        authorize(type.tenantId.toString(), "metadata:write")
        val count = em.createQuery("SELECT COUNT(o) FROM MetadataObject o WHERE o.typeId = :t", java.lang.Long::class.java).setParameter("t", type.id).singleResult
        if (count > 0) return error(409, "Type still has $count objects")
        em.remove(type)
        return Response.noContent().build()
    }

    // ── Templates ────────────────────────────────────────────────────────────

    private fun template(key: String): Map<String, Any?>? =
        javaClass.getResourceAsStream("/metadata-templates/$key.json")?.use {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
        }

    @GET
    @Path("/templates")
    fun listTemplates(): Response {
        currentUser() ?: return error(401, "Unauthorized")
        return Response.ok(TEMPLATES.mapNotNull { key ->
            template(key)?.let { t -> mapOf("key" to t["key"], "name" to t["name"], "description" to t["description"],
                "types" to (t["types"] as List<*>).map { (it as Map<*, *>)["typeKey"] }) }
        }).build()
    }

    /** Installs the template's types that the tenant does not have yet. */
    @POST
    @Path("/templates/{key}/install")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    fun installTemplate(@PathParam("key") key: String, @QueryParam("tenantId") tenantId: String?): Response {
        val tid = authorize(tenantId, "metadata:write")
        val tpl = template(key)?.takeIf { key in TEMPLATES } ?: return error(404, "Template not found")
        val existing = em.createQuery("SELECT t.typeKey FROM MetadataType t WHERE t.tenantId = :t", String::class.java)
            .setParameter("t", tid).resultList.toSet()
        val installed = (tpl["types"] as List<*>).filterIsInstance<Map<*, *>>().filter { it["typeKey"] !in existing }.map { t ->
            MetadataType().apply {
                this.tenantId = tid; typeKey = t["typeKey"].toString(); name = t["name"].toString(); description = t["description"]?.toString()
                attributeSchema = json(t["attributeSchema"]); identifierSchemes = json(t["identifierSchemes"]); relationSchema = json(t["relationSchema"])
                template = key
            }.also { em.persist(it) }.typeKey
        }
        return Response.ok(mapOf("installed" to installed, "skipped" to (tpl["types"] as List<*>).size - installed.size)).build()
    }

    // ── Objects ──────────────────────────────────────────────────────────────

    @GET
    @Path("/objects")
    fun listObjects(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("type") typeKey: String?,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response {
        val tid = authorize(tenantId, "metadata:read")
        val typeId = typeKey?.takeIf { it.isNotBlank() }?.let { validated { metadata.type(tid, it) }.id }
        val where = buildString {
            append("o.tenantId = :t")
            if (typeId != null) append(" AND o.typeId = :type")
            if (!q.isNullOrBlank()) append(" AND (LOWER(o.name) LIKE :q OR EXISTS (SELECT i FROM MetadataIdentifier i WHERE i.objectId = o.id AND LOWER(i.value) LIKE :q))")
        }
        fun <T> bind(query: jakarta.persistence.TypedQuery<T>) = query.setParameter("t", tid).also {
            if (typeId != null) it.setParameter("type", typeId)
            if (!q.isNullOrBlank()) it.setParameter("q", "%${q.trim().lowercase()}%")
        }
        val total = bind(em.createQuery("SELECT COUNT(o) FROM MetadataObject o WHERE $where", java.lang.Long::class.java)).singleResult
        val objects = bind(em.createQuery("SELECT o FROM MetadataObject o WHERE $where ORDER BY o.name", MetadataObject::class.java))
            .setFirstResult(offset.coerceAtLeast(0)).setMaxResults(limit.coerceIn(1, 500)).resultList
        val types = em.createQuery("SELECT t FROM MetadataType t WHERE t.tenantId = :t", MetadataType::class.java).setParameter("t", tid).resultList.associateBy { it.id }
        val now = Instant.now()
        return Response.ok(mapOf("total" to total, "items" to objects.map { o ->
            val view = metadata.view(o.id!!, now)
            mapOf("id" to o.id, "name" to o.name, "typeKey" to types[o.typeId]?.typeKey, "typeName" to types[o.typeId]?.name,
                "identifiers" to view?.identifiers.orEmpty(), "attributes" to view?.attributes.orEmpty(), "updatedAt" to o.updatedAt)
        })).build()
    }

    @GET
    @Path("/objects/{id}")
    fun getObject(@PathParam("id") id: String, @QueryParam("at") at: String?): Response {
        val obj = runCatching { UUID.fromString(id) }.getOrNull()?.let { em.find(MetadataObject::class.java, it) } ?: return error(404, "Object not found")
        authorize(obj.tenantId.toString(), "metadata:read")
        val atTime = at?.let { parseInstant(it) ?: return error(400, "Invalid at") } ?: Instant.now()
        val type = em.find(MetadataType::class.java, obj.typeId)
        fun name(oid: UUID?) = oid?.let { em.find(MetadataObject::class.java, it) }?.let { o ->
            mapOf("id" to o.id, "name" to o.name, "typeKey" to em.find(MetadataType::class.java, o.typeId)?.typeKey)
        }
        val view = metadata.view(obj.id!!, atTime)
        return Response.ok(mapOf(
            "id" to obj.id, "name" to obj.name, "typeKey" to type?.typeKey, "typeName" to type?.name, "at" to atTime,
            "identifiers" to view?.identifiers.orEmpty(),
            "attributes" to view?.attributes.orEmpty(),
            "identifierHistory" to em.createQuery("SELECT i FROM MetadataIdentifier i WHERE i.objectId = :o ORDER BY i.scheme, i.validFrom", MetadataIdentifier::class.java)
                .setParameter("o", obj.id).resultList.map { mapOf("scheme" to it.scheme, "value" to it.value, "validFrom" to it.validFrom, "validTo" to it.validTo) },
            "attributeHistory" to em.createQuery("SELECT a FROM MetadataAttributeVersion a WHERE a.objectId = :o ORDER BY a.validFrom", MetadataAttributeVersion::class.java)
                .setParameter("o", obj.id).resultList.map { mapOf("attributes" to metadata.parseMap(it.attributes), "validFrom" to it.validFrom, "validTo" to it.validTo, "source" to it.source, "changedAt" to it.changedAt) },
            "relations" to em.createQuery("SELECT r FROM MetadataRelation r WHERE r.fromObjectId = :o ORDER BY r.relation, r.validFrom", MetadataRelation::class.java)
                .setParameter("o", obj.id).resultList.map { mapOf("relation" to it.relation, "target" to name(it.toObjectId), "validFrom" to it.validFrom, "validTo" to it.validTo) },
            "referencedBy" to em.createQuery("SELECT r FROM MetadataRelation r WHERE r.toObjectId = :o ORDER BY r.relation, r.validFrom", MetadataRelation::class.java)
                .setParameter("o", obj.id).setMaxResults(200).resultList.map { mapOf("relation" to it.relation, "source" to name(it.fromObjectId), "validFrom" to it.validFrom, "validTo" to it.validTo) },
        )).build()
    }

    /**
     * Creates or updates one object. Body: `{"type", "match": {"scheme", "value"}, "name",
     * "identifiers": {scheme: value}, "attributes": {...}, "replaceAttributes", "relations":
     * [{"relation", "type", "scheme", "value", "create"}], "validFrom"}`.
     */
    @POST
    @Path("/objects/upsert")
    @Transactional
    fun upsert(@QueryParam("tenantId") tenantId: String?, body: Map<String, Any?>): Response {
        val tid = authorize(tenantId, "metadata:write")
        val match = body["match"] as? Map<*, *> ?: return error(400, "match with scheme and value is required")
        val typeKey = body["type"]?.toString() ?: return error(400, "type is required")
        @Suppress("UNCHECKED_CAST")
        val result = validated {
            metadata.upsert(tid, UpsertRequest(
                typeKey = typeKey,
                match = ObjectRef(typeKey, match["scheme"]?.toString() ?: throw MetadataValidationException("match.scheme is required"),
                    match["value"]?.toString() ?: throw MetadataValidationException("match.value is required")),
                name = body["name"]?.toString(),
                identifiers = (body["identifiers"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() },
                attributes = (body["attributes"] as? Map<String, Any?>).orEmpty(),
                replaceAttributes = body["replaceAttributes"] == true,
                relations = (body["relations"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { r ->
                    RelationChange(r["relation"].toString(), ObjectRef(r["type"]?.toString(), r["scheme"].toString(), r["value"].toString(), r["create"] == true))
                },
                validFrom = body["validFrom"]?.let { parseInstant(it) ?: throw MetadataValidationException("Invalid validFrom") },
                source = "api:${currentUser()?.email}",
            ))
        }
        return Response.ok(mapOf("id" to result.objectId, "created" to result.created, "changed" to result.changed)).build()
    }

    /**
     * Bulk import (e.g. a CSV converted by the UI): `{"mapping": <saveMetadata config>, "records": [...]}`.
     * Idempotent: importing the same file again changes nothing.
     */
    @POST
    @Path("/objects/import")
    fun import(@QueryParam("tenantId") tenantId: String?, body: Map<String, Any?>): Response {
        val tid = authorize(tenantId, "metadata:write")
        val mapping = body["mapping"] as? Map<*, *> ?: return error(400, "mapping is required")
        val records = body["records"] as? List<*> ?: return error(400, "records is required")
        if (records.size > 50_000) return error(400, "At most 50,000 records per import")
        val result = validated { nodes.save(tid, SaveMetadataConfig.parse(mapping), records, source = "import:${currentUser()?.email}") }
        return Response.ok(mapOf("created" to result.created, "changed" to result.changed, "unchanged" to result.unchanged)).build()
    }

    @DELETE
    @Path("/objects/{id}")
    @Transactional
    fun deleteObject(@PathParam("id") id: String): Response {
        val obj = runCatching { UUID.fromString(id) }.getOrNull()?.let { em.find(MetadataObject::class.java, it) } ?: return error(404, "Object not found")
        authorize(obj.tenantId.toString(), "metadata:write")
        em.remove(obj)
        em.flush()
        metadata.invalidateAll()
        return Response.noContent().build()
    }

    /** What an enrichment would add: `?scheme=lorawan.devEUI&value=...&path=meter,metering_location&at=...`. */
    @GET
    @Path("/resolve")
    fun resolve(
        @QueryParam("tenantId") tenantId: String?,
        @QueryParam("scheme") scheme: String?,
        @QueryParam("value") value: String?,
        @QueryParam("path") path: String?,
        @QueryParam("at") at: String?,
    ): Response {
        val tid = authorize(tenantId, "metadata:read")
        if (scheme.isNullOrBlank() || value.isNullOrBlank()) return error(400, "scheme and value are required")
        val atTime = at?.let { parseInstant(it) ?: return error(400, "Invalid at") } ?: Instant.now()
        val start = metadata.resolve(tid, scheme, value, atTime) ?: return error(404, "No object with $scheme=$value at $atTime")
        val relations = path?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return Response.ok(metadata.follow(start, relations, atTime).mapNotNull { metadata.view(it, atTime)?.toRecordValue() }).build()
    }

    private companion object {
        val KEY = Regex("^[a-z][a-z0-9_]{0,99}$")
        val TEMPLATES = listOf("energy", "iot")
    }
}
