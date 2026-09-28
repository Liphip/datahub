package com.dataturntable.metadata

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Reference to an object by one of its identifiers, optionally created when missing. */
data class ObjectRef(
    val typeKey: String?,
    val scheme: String,
    val value: String,
    val createIfMissing: Boolean = false,
    val name: String? = null,
)

data class RelationChange(val relation: String, val target: ObjectRef)

/**
 * Creates or updates an object, identified by [match]. Changes take effect at [validFrom]
 * (null: now); the previous state stays queryable before that time. Without [validFrom], the
 * first known value of an attribute set, identifier or relation is valid since always, so
 * readings from before the metadata was loaded are enriched too.
 */
data class UpsertRequest(
    val typeKey: String,
    val match: ObjectRef,
    val name: String? = null,
    val identifiers: Map<String, String> = emptyMap(),
    val attributes: Map<String, Any?> = emptyMap(),
    /** true: [attributes] replace the version; false: they are merged into it. */
    val replaceAttributes: Boolean = false,
    val relations: List<RelationChange> = emptyList(),
    val validFrom: Instant? = null,
    val source: String? = null,
)

data class UpsertResult(val objectId: UUID, val created: Boolean, val changed: Boolean)

/** State of an object at a point in time, as used for enrichment and the API. */
data class ObjectView(
    val id: UUID,
    val name: String?,
    val typeKey: String?,
    val identifiers: Map<String, String>,
    val attributes: Map<String, Any?>,
) {
    /** Shape added to records by enrichment: attributes plus `_id`, `_type`, `_name` and `ids`. */
    fun toRecordValue(): Map<String, Any?> =
        attributes + mapOf("_id" to id.toString(), "_type" to typeKey, "_name" to name, "ids" to identifiers)
}

class MetadataValidationException(message: String) : IllegalArgumentException(message)

/**
 * Metadata store with point-in-time reads.
 *
 * Reads for enrichment go through a short-lived cache (default 30 s), so enriching
 * millions of records does not query the database for each one; changes reach other
 * replicas within that time. Writes invalidate the local cache immediately.
 */
@ApplicationScoped
class MetadataService {

    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.metadata.cache-ttl", defaultValue = "30s")
    lateinit var cacheTtl: Duration

    private data class Cached<T>(val value: T, val loadedAt: Long)
    private data class IdRow(val objectId: UUID, val from: Instant?, val to: Instant?)
    private data class AttrRow(val attributes: Map<String, Any?>, val from: Instant?, val to: Instant?)
    private data class RelRow(val relation: String, val target: UUID, val from: Instant?, val to: Instant?)
    private data class ObjRow(val name: String?, val typeKey: String?, val ids: List<Triple<String, String, Pair<Instant?, Instant?>>>)

    private val identifierCache = ConcurrentHashMap<String, Cached<List<IdRow>>>()
    private val attributeCache = ConcurrentHashMap<UUID, Cached<List<AttrRow>>>()
    private val relationCache = ConcurrentHashMap<UUID, Cached<List<RelRow>>>()
    private val objectCache = ConcurrentHashMap<UUID, Cached<ObjRow>>()

    private fun <K, T> cached(cache: ConcurrentHashMap<K, Cached<T>>, key: K, load: () -> T): T {
        val now = System.nanoTime()
        cache[key]?.takeIf { now - it.loadedAt < cacheTtl.toNanos() }?.let { return it.value }
        return load().also { cache[key] = Cached(it, now) }
    }

    fun invalidateAll() {
        identifierCache.clear(); attributeCache.clear(); relationCache.clear(); objectCache.clear()
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Object holding identifier [scheme]=[value] at [at]. */
    fun resolve(tenantId: UUID, scheme: String, value: String, at: Instant = Instant.now()): UUID? =
        cached(identifierCache, "$tenantId|$scheme|$value") {
            em.createQuery(
                "SELECT i FROM MetadataIdentifier i WHERE i.tenantId = :t AND i.scheme = :s AND i.value = :v",
                MetadataIdentifier::class.java
            ).setParameter("t", tenantId).setParameter("s", scheme).setParameter("v", value).resultList
                .map { IdRow(it.objectId!!, it.validFrom, it.validTo) }
        }.firstOrNull { validAt(it.from, it.to, at) }?.objectId

    /** Objects reached from [objectId] by following [path] (relation names) at [at]. */
    fun follow(objectId: UUID, path: List<String>, at: Instant = Instant.now()): List<UUID> =
        path.fold(listOf(objectId)) { current, relation ->
            current.flatMap { id -> relations(id).filter { it.relation == relation && validAt(it.from, it.to, at) }.map { it.target } }.distinct()
        }

    fun view(objectId: UUID, at: Instant = Instant.now()): ObjectView? {
        val obj = cached(objectCache, objectId) {
            val o = em.find(MetadataObject::class.java, objectId) ?: return@cached ObjRow(null, null, emptyList())
            val typeKey = o.typeId?.let { em.find(MetadataType::class.java, it)?.typeKey }
            val ids = em.createQuery("SELECT i FROM MetadataIdentifier i WHERE i.objectId = :o", MetadataIdentifier::class.java)
                .setParameter("o", objectId).resultList.map { Triple(it.scheme!!, it.value!!, it.validFrom to it.validTo) }
            ObjRow(o.name, typeKey, ids)
        }
        if (obj.typeKey == null) return null
        return ObjectView(
            id = objectId,
            name = obj.name,
            typeKey = obj.typeKey,
            identifiers = obj.ids.filter { validAt(it.third.first, it.third.second, at) }.associate { it.first to it.second },
            attributes = attributes(objectId).firstOrNull { validAt(it.from, it.to, at) }?.attributes.orEmpty(),
        )
    }

    private fun attributes(objectId: UUID): List<AttrRow> = cached(attributeCache, objectId) {
        em.createQuery("SELECT a FROM MetadataAttributeVersion a WHERE a.objectId = :o", MetadataAttributeVersion::class.java)
            .setParameter("o", objectId).resultList.map { AttrRow(parseMap(it.attributes), it.validFrom, it.validTo) }
    }

    private fun relations(objectId: UUID): List<RelRow> = cached(relationCache, objectId) {
        em.createQuery("SELECT r FROM MetadataRelation r WHERE r.fromObjectId = :o", MetadataRelation::class.java)
            .setParameter("o", objectId).resultList.map { RelRow(it.relation!!, it.toObjectId!!, it.validFrom, it.validTo) }
    }

    // ── Writes (inside the caller's transaction) ─────────────────────────────

    fun type(tenantId: UUID, typeKey: String): MetadataType =
        em.createQuery("SELECT t FROM MetadataType t WHERE t.tenantId = :t AND t.typeKey = :k", MetadataType::class.java)
            .setParameter("t", tenantId).setParameter("k", typeKey).resultList.firstOrNull()
            ?: throw MetadataValidationException("Unknown metadata type '$typeKey'")

    /**
     * Converts values to the attribute types of the schema, so a CSV or SAP export with
     * `"3"` or `"4,2"` stores numbers that pipelines can compute with. Blank values are null.
     */
    fun coerceAttributes(type: MetadataType, attributes: Map<String, Any?>): Map<String, Any?> {
        val types = parseList(type.attributeSchema).associate { it["name"]?.toString() to it["type"]?.toString() }
        return attributes.mapValues { (name, value) ->
            val text = (value as? String)?.trim()
            when {
                value == null || text?.isEmpty() == true -> null
                text == null -> value
                types[name] == "number" -> (text.takeIf { '.' in it } ?: text.replace(',', '.')).let { n ->
                    n.toLongOrNull() ?: n.toDoubleOrNull()
                        ?: throw MetadataValidationException("Attribute '$name' must be a number, got '$text'")
                }
                types[name] == "boolean" -> when (text.lowercase()) {
                    "true", "1", "yes", "ja", "x" -> true
                    "false", "0", "no", "nein", "" -> false
                    else -> throw MetadataValidationException("Attribute '$name' must be true or false, got '$text'")
                }
                else -> text
            }
        }
    }

    fun upsert(tenantId: UUID, request: UpsertRequest): UpsertResult {
        val type = type(tenantId, request.typeKey)
        validateIdentifier(type, request.match.scheme, request.match.value)
        request.identifiers.forEach { (scheme, value) -> validateIdentifier(type, scheme, value) }

        var created = false
        var changed = false
        val lookupAt = request.validFrom ?: Instant.now()
        // Sources know an object by different identifiers and arrive in any order: an object
        // another source created (e.g. a meter known only by its number from the device registry)
        // is found through the other identifiers of this record and completed, not duplicated.
        val byMatch = resolveForWrite(tenantId, request.match.scheme, request.match.value, lookupAt)
        val byOther = if (byMatch != null) null else request.identifiers.entries.firstNotNullOfOrNull { (scheme, value) ->
            resolveForWrite(tenantId, scheme, value, lookupAt)?.takeIf { em.find(MetadataObject::class.java, it)?.typeId == type.id }
        }
        val objectId = byMatch ?: byOther ?: run {
            created = true
            createObject(tenantId, type, request.name ?: request.match.value, request.match.scheme, request.match.value, request.validFrom)
        }
        // Changes without a time take effect now; first values (see setX) since always.
        val at: Instant? = if (created) request.validFrom else request.validFrom ?: Instant.now()
        val explicit = request.validFrom != null
        val obj = em.find(MetadataObject::class.java, objectId)
        if (obj.typeId != type.id) throw MetadataValidationException(
            "Identifier ${request.match.scheme}=${request.match.value} belongs to an object of another type")
        if (request.name != null && request.name != obj.name) { obj.name = request.name; changed = true }

        if (byOther != null && setIdentifier(tenantId, objectId, request.match.scheme, request.match.value, at, explicit)) changed = true
        request.identifiers.forEach { (scheme, value) -> if (setIdentifier(tenantId, objectId, scheme, value, at, explicit)) changed = true }
        if (request.attributes.isNotEmpty() && setAttributes(objectId, coerceAttributes(type, request.attributes), request.replaceAttributes, at, explicit, request.source)) changed = true
        val relationSchema = parseList(type.relationSchema)
        request.relations.forEach { change ->
            val many = relationSchema.firstOrNull { it["relation"] == change.relation }?.get("many") == true
            val target = resolveForWrite(tenantId, change.target.scheme, change.target.value, at)
                ?: if (change.target.createIfMissing) {
                    val targetType = type(tenantId, change.target.typeKey
                        ?: throw MetadataValidationException("Relation '${change.relation}': target type required to create ${change.target.scheme}=${change.target.value}"))
                    validateIdentifier(targetType, change.target.scheme, change.target.value)
                    // A new target is known since always unless the change carries its own time.
                    createObject(tenantId, targetType, change.target.name ?: change.target.value, change.target.scheme, change.target.value, request.validFrom)
                } else throw MetadataValidationException("Relation '${change.relation}': no object with ${change.target.scheme}=${change.target.value}")
            if (setRelation(tenantId, objectId, change.relation, target, many, at, explicit, request.source)) changed = true
        }
        if (changed || created) obj.updatedAt = Instant.now()
        em.flush()
        invalidateAll()
        return UpsertResult(objectId, created, changed || created)
    }

    fun createObject(tenantId: UUID, type: MetadataType, name: String, scheme: String?, value: String?, validFrom: Instant?): UUID {
        val obj = MetadataObject().apply { this.tenantId = tenantId; typeId = type.id; this.name = name }
        em.persist(obj)
        em.flush()
        if (scheme != null && value != null) setIdentifier(tenantId, obj.id!!, scheme, value, validFrom, validFrom != null)
        return obj.id!!
    }

    private fun resolveForWrite(tenantId: UUID, scheme: String, value: String, at: Instant?): UUID? {
        val rows = identifierRows(tenantId, scheme, value)
        return (if (at == null) rows.firstOrNull { it.validTo == null } else rows.firstOrNull { validAt(it.validFrom, it.validTo, at) }
            ?: rows.firstOrNull { it.validTo == null })?.objectId
    }

    private fun identifierRows(tenantId: UUID, scheme: String, value: String) =
        em.createQuery("SELECT i FROM MetadataIdentifier i WHERE i.tenantId = :t AND i.scheme = :s AND i.value = :v", MetadataIdentifier::class.java)
            .setParameter("t", tenantId).setParameter("s", scheme).setParameter("v", value).resultList

    /** Gives [objectId] identifier [scheme]=[value] from [at]; an identifier moves away from its previous holder. */
    private fun setIdentifier(tenantId: UUID, objectId: UUID, scheme: String, value: String, changeAt: Instant?, explicit: Boolean): Boolean {
        val mine = em.createQuery("SELECT i FROM MetadataIdentifier i WHERE i.objectId = :o AND i.scheme = :s", MetadataIdentifier::class.java)
            .setParameter("o", objectId).setParameter("s", scheme).resultList
        val heldByOther = identifierRows(tenantId, scheme, value).any { it.objectId != objectId }
        val at = if (!explicit && mine.isEmpty() && !heldByOther) null else changeAt
        val current = mine.firstOrNull { if (at == null) it.validTo == null else validAt(it.validFrom, it.validTo, at) }
        if (current?.value == value) return false
        // The value held by another object at that time moves to this one.
        identifierRows(tenantId, scheme, value)
            .filter { it.objectId != objectId && (at == null || validAt(it.validFrom, it.validTo, at) || it.validFrom?.isAfter(at) == true) }
            .forEach { other -> closeOrDelete(other, at) { f, t -> other.validFrom = f; other.validTo = t } }
        val end = current?.validTo
        current?.let { closeOrDelete(it, at) { f, t -> it.validFrom = f; it.validTo = t } }
        em.persist(MetadataIdentifier().apply {
            this.tenantId = tenantId; this.objectId = objectId; this.scheme = scheme; this.value = value
            validFrom = at; validTo = end
        })
        return true
    }

    private fun setAttributes(objectId: UUID, attributes: Map<String, Any?>, replace: Boolean, changeAt: Instant?, explicit: Boolean, source: String?): Boolean {
        val versions = em.createQuery("SELECT a FROM MetadataAttributeVersion a WHERE a.objectId = :o ORDER BY a.validFrom", MetadataAttributeVersion::class.java)
            .setParameter("o", objectId).resultList
        val at = if (!explicit && versions.isEmpty()) null else changeAt
        val current = versions.firstOrNull { if (at == null) it.validTo == null else validAt(it.validFrom, it.validTo, at) }
        val base = if (replace) emptyMap() else current?.let { parseMap(it.attributes) }.orEmpty()
        val merged = (base + attributes).filterValues { it != null }
        if (current != null && parseMap(current.attributes) == merged) return false
        // A version starting before any existing one ends where the next one starts.
        val end = current?.validTo ?: versions.firstOrNull { at != null && it.validFrom != null && it.validFrom!!.isAfter(at) }?.validFrom
        if (current != null && current.validFrom == at) {
            current.attributes = objectMapper.writeValueAsString(merged)
            current.source = source
            current.changedAt = Instant.now()
            return true
        }
        current?.let { it.validTo = at }
        em.persist(MetadataAttributeVersion().apply {
            this.objectId = objectId; this.attributes = objectMapper.writeValueAsString(merged)
            validFrom = at; validTo = end; this.source = source
        })
        return true
    }

    private fun setRelation(tenantId: UUID, fromId: UUID, relation: String, toId: UUID, many: Boolean, changeAt: Instant?, explicit: Boolean, source: String?): Boolean {
        val existing = em.createQuery("SELECT r FROM MetadataRelation r WHERE r.fromObjectId = :o AND r.relation = :r", MetadataRelation::class.java)
            .setParameter("o", fromId).setParameter("r", relation).resultList
        val at = if (!explicit && existing.isEmpty()) null else changeAt
        val active = existing.filter { if (at == null) it.validTo == null else validAt(it.validFrom, it.validTo, at) }
        if (active.any { it.toObjectId == toId }) return false
        var end: Instant? = null
        if (!many) active.forEach { old -> end = old.validTo; closeOrDelete(old, at) { f, t -> old.validFrom = f; old.validTo = t } }
        em.persist(MetadataRelation().apply {
            this.tenantId = tenantId; fromObjectId = fromId; this.relation = relation; toObjectId = toId
            validFrom = at; validTo = end; this.source = source
        })
        return true
    }

    /** Ends a period at [at]; without a time (or when it would be empty) the row is removed. */
    private fun <T : Any> closeOrDelete(row: T, at: Instant?, set: (Instant?, Instant?) -> Unit) {
        val from = when (row) { is MetadataIdentifier -> row.validFrom; is MetadataRelation -> row.validFrom; else -> null }
        if (at == null || (from != null && !from.isBefore(at))) em.remove(row)
        else set(from, at)
    }

    private fun validateIdentifier(type: MetadataType, scheme: String, value: String) {
        val spec = parseList(type.identifierSchemes).firstOrNull { it["scheme"] == scheme } ?: return
        val pattern = spec["pattern"]?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (!Regex(pattern).matches(value)) {
            throw MetadataValidationException("'$value' is not a valid ${spec["name"] ?: scheme} (expected $pattern)")
        }
    }

    fun parseMap(raw: String?): Map<String, Any?> =
        raw?.takeIf { it.isNotBlank() }?.let { objectMapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }.orEmpty()

    fun parseList(raw: String?): List<Map<String, Any?>> =
        raw?.takeIf { it.isNotBlank() }?.let { objectMapper.readValue(it, object : TypeReference<List<Map<String, Any?>>>() {}) }.orEmpty()
}
