package com.dataturntable.metadata

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Field path (dotted) of a record, e.g. `deviceInfo.devEui`. */
internal fun resolvePath(record: Map<String, Any?>, path: String): Any? =
    path.split('.').fold(record as Any?) { cur, part -> (cur as? Map<*, *>)?.get(part) }

/** Epoch seconds/milliseconds, ISO-8601 instants, offset date-times or dates. */
internal fun parseInstant(raw: Any?): Instant? = when (raw) {
    null -> null
    is Number -> raw.toDouble().let { if (it < 1e12) Instant.ofEpochMilli((it * 1000).toLong()) else Instant.ofEpochMilli(it.toLong()) }
    else -> raw.toString().trim().let { text ->
        text.toDoubleOrNull()?.let { parseInstant(it) }
            ?: runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}

private fun records(input: Any?): List<Map<String, Any?>> = when (input) {
    is Map<*, *> -> listOf(input.entries.associate { it.key.toString() to it.value })
    is List<*> -> input.filterIsInstance<Map<*, *>>().map { m -> m.entries.associate { it.key.toString() to it.value } }
    else -> emptyList()
}

/**
 * Built-in PROCESSING node configuration: adds metadata to every record.
 *
 * ```
 * "enrich": {
 *   "match": {"scheme": "lorawan.devEUI", "field": "devEUI"},
 *   "timestampField": "time",
 *   "outputs": [
 *     {"as": "meter"},
 *     {"as": "malo", "path": ["market_location"]},
 *     {"as": "location", "path": ["market_location", "location"]}
 *   ],
 *   "onMissing": "keep"
 * }
 * ```
 * Each output adds the attributes, identifiers (`ids`), `_id`, `_type` and `_name` of the
 * object reached from the matched one by following `path`, as valid at the record's time.
 */
data class EnrichConfig(
    val scheme: String,
    val field: String,
    val timestampField: String?,
    val outputs: List<Output>,
    val onMissing: String,
) {
    data class Output(val name: String, val path: List<String>)

    companion object {
        private val ON_MISSING = setOf("keep", "drop", "fail")

        fun parse(raw: Map<*, *>): EnrichConfig {
            val match = raw["match"] as? Map<*, *> ?: throw MetadataValidationException("Enrichment requires 'match' with scheme and field")
            val scheme = match["scheme"]?.toString()?.trim()?.ifEmpty { null } ?: throw MetadataValidationException("Enrichment 'match' requires 'scheme'")
            val field = match["field"]?.toString()?.trim()?.ifEmpty { null } ?: throw MetadataValidationException("Enrichment 'match' requires 'field'")
            val outputs = (raw["outputs"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { o ->
                val name = o["as"]?.toString()?.trim()?.ifEmpty { null } ?: throw MetadataValidationException("Every enrichment output needs 'as'")
                Output(name, (o["path"] as? List<*>).orEmpty().mapNotNull { it?.toString()?.trim()?.ifEmpty { null } })
            }.ifEmpty { listOf(Output("metadata", emptyList())) }
            val onMissing = raw["onMissing"]?.toString()?.lowercase() ?: "keep"
            if (onMissing !in ON_MISSING) throw MetadataValidationException("Enrichment 'onMissing' must be one of ${ON_MISSING.joinToString()}")
            return EnrichConfig(scheme, field, raw["timestampField"]?.toString()?.trim()?.ifEmpty { null }, outputs, onMissing)
        }
    }
}

/**
 * Built-in EXPORT node configuration: creates or updates metadata objects from records,
 * e.g. from an SAP export of meters.
 *
 * ```
 * "saveMetadata": {
 *   "type": "meter",
 *   "match": {"scheme": "sap.equipment", "field": "EQUNR"},
 *   "nameField": "SERNR",
 *   "identifiers": {"meter_number": "SERNR"},
 *   "attributes": {"manufacturer": "HERST", "phases": "PHASEN"},
 *   "relations": [{"relation": "market_location", "type": "market_location", "scheme": "malo", "field": "MALO", "create": true}],
 *   "validFromField": "AB"
 * }
 * ```
 */
data class SaveMetadataConfig(
    val typeKey: String,
    val scheme: String,
    val field: String,
    val nameField: String?,
    val identifiers: Map<String, String>,
    val attributes: Map<String, String>,
    val allAttributes: Boolean,
    val relations: List<Relation>,
    val validFromField: String?,
) {
    data class Relation(val relation: String, val typeKey: String?, val scheme: String, val field: String, val create: Boolean)

    companion object {
        fun parse(raw: Map<*, *>): SaveMetadataConfig {
            fun str(m: Map<*, *>, k: String) = m[k]?.toString()?.trim()?.ifEmpty { null }
            val type = str(raw, "type") ?: throw MetadataValidationException("Saving metadata requires 'type'")
            val match = raw["match"] as? Map<*, *> ?: throw MetadataValidationException("Saving metadata requires 'match' with scheme and field")
            val attributes = raw["attributes"]
            return SaveMetadataConfig(
                typeKey = type,
                scheme = str(match, "scheme") ?: throw MetadataValidationException("Saving metadata: 'match' requires 'scheme'"),
                field = str(match, "field") ?: throw MetadataValidationException("Saving metadata: 'match' requires 'field'"),
                nameField = str(raw, "nameField"),
                identifiers = (raw["identifiers"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() },
                attributes = (attributes as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() },
                allAttributes = attributes == "*",
                relations = (raw["relations"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { r ->
                    Relation(
                        relation = str(r, "relation") ?: throw MetadataValidationException("Every relation needs 'relation'"),
                        typeKey = str(r, "type"),
                        scheme = str(r, "scheme") ?: throw MetadataValidationException("Every relation needs 'scheme'"),
                        field = str(r, "field") ?: throw MetadataValidationException("Every relation needs 'field'"),
                        create = r["create"] == true,
                    ).also { if (it.create && it.typeKey == null) throw MetadataValidationException("Relation '${it.relation}' with create needs 'type'") }
                },
                validFromField = str(raw, "validFromField"),
            )
        }
    }
}

/** Executes the built-in metadata nodes of a pipeline. */
@ApplicationScoped
class MetadataNodes {

    @Inject
    lateinit var metadata: MetadataService

    data class EnrichResult(val output: Any?, val enriched: Int, val missing: Int, val dropped: Int)

    fun enrich(tenantId: UUID, config: EnrichConfig, input: Any?, now: Instant = Instant.now()): EnrichResult {
        var enriched = 0
        var missing = 0
        var dropped = 0
        val out = records(input).mapNotNull { record ->
            val key = resolvePath(record, config.field)?.toString()
            val at = config.timestampField?.let { parseInstant(resolvePath(record, it)) } ?: now
            val objectId = key?.let { metadata.resolve(tenantId, config.scheme, it, at) }
            if (objectId == null) {
                missing++
                when (config.onMissing) {
                    "fail" -> throw IllegalStateException("No metadata for ${config.scheme}=$key")
                    "drop" -> { dropped++; return@mapNotNull null }
                    else -> return@mapNotNull record
                }
            }
            enriched++
            record + config.outputs.associate { output ->
                val targets = metadata.follow(objectId, output.path, at).mapNotNull { metadata.view(it, at)?.toRecordValue() }
                output.name to when (targets.size) { 0 -> null; 1 -> targets.single(); else -> targets }
            }
        }
        val output: Any? = if (input is List<*>) out else out.firstOrNull()
        return EnrichResult(output, enriched, missing, dropped)
    }

    data class SaveResult(val created: Int, val changed: Int, val unchanged: Int)

    /** Upserts one object per record, all in one transaction (idempotent: repeating it changes nothing). */
    fun save(tenantId: UUID, config: SaveMetadataConfig, input: Any?, source: String?): SaveResult =
        QuarkusTransaction.requiringNew().call {
            var created = 0
            var changed = 0
            var unchanged = 0
            records(input).forEach { record ->
                val key = resolvePath(record, config.field)?.toString()?.trim()?.ifEmpty { null }
                    ?: throw MetadataValidationException("Record without '${config.field}' cannot be saved as ${config.typeKey}")
                val mapped = config.attributes.mapValues { (_, field) -> resolvePath(record, field) }
                val attributes = if (config.allAttributes) record.filterKeys { !it.startsWith("_") } else mapped
                val result = metadata.upsert(tenantId, UpsertRequest(
                    typeKey = config.typeKey,
                    match = ObjectRef(config.typeKey, config.scheme, key),
                    name = config.nameField?.let { resolvePath(record, it)?.toString() },
                    identifiers = config.identifiers.mapNotNull { (scheme, field) -> resolvePath(record, field)?.toString()?.let { scheme to it } }.toMap(),
                    attributes = attributes,
                    relations = config.relations.mapNotNull { r ->
                        resolvePath(record, r.field)?.toString()?.trim()?.ifEmpty { null }?.let { value ->
                            RelationChange(r.relation, ObjectRef(r.typeKey, r.scheme, value, createIfMissing = r.create))
                        }
                    },
                    validFrom = config.validFromField?.let { parseInstant(resolvePath(record, it)) },
                    source = source,
                ))
                when {
                    result.created -> created++
                    result.changed -> changed++
                    else -> unchanged++
                }
            }
            SaveResult(created, changed, unchanged)
        }
}
