package com.dataturntable.engine

import com.dataturntable.pipeline.Pipeline
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.jboss.logging.Logger
import java.util.UUID

/**
 * In-memory view of the pipelines that ingest needs: whether a pipeline accepts data, which
 * pipelines an input connector feeds, and how records are deduplicated. Refreshed every
 * few seconds, so ingest keeps accepting data while PostgreSQL fails over; a pipeline the
 * cache has not seen yet is looked up once.
 */
@ApplicationScoped
class PipelineDirectory {

    data class Entry(
        val id: UUID,
        val tenantId: UUID?,
        val active: Boolean,
        /** Field paths whose values identify a record (`deduplicateBy` on IMPORT nodes). */
        val deduplicateBy: List<String>,
        val inputConnectorIds: Set<UUID>,
    )

    private val log = Logger.getLogger(PipelineDirectory::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var em: EntityManager

    @Volatile private var entries: Map<UUID, Entry> = emptyMap()
    @Volatile private var loaded = false

    fun get(pipelineId: UUID): Entry? {
        if (!loaded) refresh()
        val cached = entries[pipelineId] ?: return loadOne(pipelineId)
        if (cached.active) return cached
        // A cached "inactive" can be up to one refresh interval old, and acting on it refuses a
        // record a fire-and-forget sender will not send again. So an inactive answer is confirmed
        // against the database before it is given; the accept path still never touches it
        // (NFR-R6), and if the database is down the cached view stands.
        return try {
            loadOne(pipelineId)
        } catch (e: Exception) {
            log.debugf("Could not confirm inactive pipeline %s, keeping the cached view: %s", pipelineId, e.message)
            cached
        }
    }

    /** Active pipelines with an IMPORT node reading from [connectorId]. */
    fun activeForConnector(connectorId: UUID): List<Entry> {
        if (!loaded) refresh()
        return entries.values.filter { it.active && connectorId in it.inputConnectorIds }
    }

    @Scheduled(every = "\${dataturntable.pipeline.directory-refresh:10s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledRefresh() {
        try {
            refresh()
        } catch (e: Exception) {
            log.debugf("Pipeline directory refresh failed, keeping the cached view: %s", e.message)
        }
    }

    fun refresh() {
        val pipelines = QuarkusTransaction.requiringNew().call {
            em.createQuery("SELECT p FROM Pipeline p", Pipeline::class.java).resultList.map(::toEntry)
        }
        entries = pipelines.associateBy { it.id }
        loaded = true
    }

    private fun loadOne(pipelineId: UUID): Entry? {
        val entry = QuarkusTransaction.requiringNew().call { em.find(Pipeline::class.java, pipelineId)?.let(::toEntry) }
            ?: return null
        entries = entries + (pipelineId to entry)
        return entry
    }

    private fun toEntry(p: Pipeline): Entry {
        val nodes = p.graphDefinition
            ?.let { runCatching { objectMapper.readValue(it, Map::class.java)["nodes"] as? List<*> }.getOrNull() }
            .orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["type"]?.toString()?.uppercase() == "IMPORT" }
        return Entry(
            id = p.id!!,
            tenantId = p.tenant?.id,
            active = p.isActive,
            deduplicateBy = nodes.flatMap { (it["deduplicateBy"] as? List<*>).orEmpty().mapNotNull { f -> f?.toString()?.trim()?.ifEmpty { null } } }.distinct(),
            inputConnectorIds = nodes.mapNotNull { n -> n["connectorId"]?.toString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } }.toSet(),
        )
    }

    companion object {
        /**
         * Idempotency key of a record from its [fields] (dotted paths), or null when a field is
         * missing — then the record cannot be told apart from a genuine repeat and is not deduplicated.
         */
        fun recordKey(record: Any?, fields: List<String>): String? {
            if (fields.isEmpty() || record !is Map<*, *>) return null
            val values = fields.map { path ->
                path.split('.').fold(record as Any?) { cur, part -> (cur as? Map<*, *>)?.get(part) } ?: return null
            }
            return fields.zip(values).joinToString("|") { (f, v) -> "$f=$v" }
        }
    }
}
