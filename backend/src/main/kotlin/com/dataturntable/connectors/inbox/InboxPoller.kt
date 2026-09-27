package com.dataturntable.connectors.inbox

import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.Connector.ConnectorType
import com.dataturntable.connectors.ConnectorRuntimeService
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.pipeline.TriggerType
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/** Pull connectors polled as inboxes. */
val INBOX_TYPES = setOf(ConnectorType.FILE, ConnectorType.FTP, ConnectorType.SFTP, ConnectorType.EMAIL)

/**
 * Ledger of the items a pull connector has read. It makes reading exactly-once although the
 * source is changed (moved, deleted, flagged) only after the data is in the pipeline queue:
 *
 * - QUEUED: every chunk is in Kafka; the source item is not yet acknowledged.
 * - DONE: acknowledged at the source (or kept, for read-only sources).
 * - FAILED: the content cannot be parsed or is too large; the item stays in the source.
 */
@Entity
@Table(name = "connector_inbox_items")
open class ConnectorInboxItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "connector_id", nullable = false)
    var connectorId: UUID? = null

    @Column(name = "item_key", nullable = false, length = 1024)
    var itemKey: String? = null

    @Column(length = 1024)
    var name: String? = null

    @Column
    var size: Long? = null

    @Column(nullable = false, length = 20)
    var status: String = "QUEUED"

    @Column
    var chunks: Int = 0

    @Column(name = "records")
    var records: Int? = null

    @Column(columnDefinition = "text")
    var error: String? = null

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.now()

    @Column(name = "queued_at")
    var queuedAt: Instant? = null

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null
}

/**
 * Polls pull connectors (directory, FTP/FTPS, SFTP, IMAP) as inboxes. Each item is read,
 * split into records and queued for every pipeline using the connector with a deterministic
 * run id per item and chunk; only then is it acknowledged at the source. Whatever step a
 * crash interrupts, the next poll finds the item again and its runs deduplicate, so senders
 * that just drop a file or send a mail need no confirmation.
 *
 * Queueing does not use the local ingest spool: the source keeps the data until Kafka has it.
 */
@ApplicationScoped
class InboxPoller {

    private val log = Logger.getLogger(InboxPoller::class.java)

    @Inject lateinit var em: EntityManager
    @Inject lateinit var runQueue: PipelineRunQueue
    @Inject lateinit var directory: PipelineDirectory
    @Inject lateinit var connectorRuntime: ConnectorRuntimeService

    @ConfigProperty(name = "dataturntable.connectors.file.base-dir", defaultValue = "/data/files")
    lateinit var fileBaseDir: String

    private data class Planned(val part: Int, val index: Int, val partName: String, val chunk: InboxParser.Chunk)

    data class PollResult(val queued: Int, val acknowledged: Int, val failed: Int, val skipped: Int, val waiting: Int)

    fun open(connector: Connector, config: Map<String, Any?>): InboxSource = when (connector.connectorType) {
        ConnectorType.FILE -> DirectorySource(FileSandbox(fileBaseDir), config)
        ConnectorType.FTP -> FtpSource(config)
        ConnectorType.SFTP -> SftpSource(config)
        ConnectorType.EMAIL -> ImapSource(config)
        else -> throw IllegalArgumentException("${connector.connectorType} is not an inbox connector")
    }

    fun poll(connectorId: UUID): PollResult {
        val (connector, config) = QuarkusTransaction.requiringNew().call {
            val c = em.find(Connector::class.java, connectorId) ?: return@call null
            c to connectorRuntime.decryptConfig(c)
        } ?: return PollResult(0, 0, 0, 0, 0)
        if (!connector.isActive) return PollResult(0, 0, 0, 0, 0)
        // Without a pipeline the items stay where they are.
        val pipelines = directory.activeForConnector(connectorId)
        if (pipelines.isEmpty()) return PollResult(0, 0, 0, 0, 0)

        val parser = InboxParser()
        val maxItems = config.int("maxItemsPerPoll", 100)
        var queued = 0; var acknowledged = 0; var failed = 0; var skipped = 0
        open(connector, config).use { source ->
            val items = source.list()
            val batch = items.take(maxItems)
            val ledger = ledgerFor(connectorId, batch.map { it.key })
            for (item in batch) {
                val row = ledger[item.key]
                when (row?.status) {
                    "DONE", "FAILED" -> { skipped++; continue }
                    "QUEUED" -> {
                        // Queued earlier, acknowledgement failed or was interrupted.
                        acknowledge(source, item, row.id!!)
                        acknowledged++
                        continue
                    }
                }
                // Read errors (network, permissions) fail the poll and the item is tried again;
                // content that cannot be parsed is recorded as FAILED and left in the source.
                val contents = source.read(item)
                val chunks = try {
                    contents.flatMapIndexed { part, content ->
                        parser.parse(content, config.str("format"), config.str("encoding"), config.str("csvDelimiter"))
                            .mapIndexed { index, chunk -> Planned(part, index, content.name, chunk) }
                    }
                } catch (e: Exception) {
                    log.warnf("Connector %s: %s cannot be parsed: %s", connectorId, item.name, e.message)
                    record(connectorId, item, "FAILED", 0, null, e.message ?: e.javaClass.simpleName)
                    failed++
                    continue
                }
                for (pipeline in pipelines) {
                    chunks.forEachIndexed { n, planned ->
                        runQueue.enqueue(
                            pipelineId = pipeline.id,
                            triggerType = TriggerType.SCHEDULED,
                            data = mapOf(
                                "_connectorId" to connectorId.toString(),
                                "_triggerType" to "INBOX",
                                "_source" to mapOf(
                                    "connector" to connector.name,
                                    "item" to item.name,
                                    "part" to planned.partName,
                                    "chunk" to n + 1,
                                    "chunks" to chunks.size,
                                    "records" to planned.chunk.records,
                                    "modified" to item.modified?.toString(),
                                ) + item.meta,
                                "payload" to planned.chunk.payload,
                            ),
                            partitionKey = "inbox:$connectorId",
                            idempotencyKey = "inbox|$connectorId|${item.key}|${planned.part}|${planned.index}",
                            allowSpool = false,
                        )
                    }
                }
                val records = chunks.mapNotNull { it.chunk.records }.takeIf { it.isNotEmpty() }?.sum()
                val id = record(connectorId, item, "QUEUED", chunks.size, records, null)
                queued++
                acknowledge(source, item, id)
                acknowledged++
            }
            val waiting = items.size - batch.size
            if (queued + failed > 0) log.infof("Connector %s (%s): %d items queued, %d failed, %d waiting", connectorId, connector.name, queued, failed, waiting)
            return PollResult(queued, acknowledged, failed, skipped, waiting)
        }
    }

    private fun acknowledge(source: InboxSource, item: InboxItem, rowId: UUID) {
        source.acknowledge(item)
        QuarkusTransaction.requiringNew().run {
            em.find(ConnectorInboxItem::class.java, rowId)?.apply { status = "DONE"; acknowledgedAt = Instant.now() }
        }
    }

    private fun ledgerFor(connectorId: UUID, keys: List<String>): Map<String, ConnectorInboxItem> =
        if (keys.isEmpty()) emptyMap() else QuarkusTransaction.requiringNew().call {
            keys.chunked(500).flatMap { part ->
                em.createQuery("SELECT i FROM ConnectorInboxItem i WHERE i.connectorId = :c AND i.itemKey IN :k", ConnectorInboxItem::class.java)
                    .setParameter("c", connectorId).setParameter("k", part).resultList
            }.associateBy { it.itemKey!! }
        }

    private fun record(connectorId: UUID, item: InboxItem, status: String, chunks: Int, records: Int?, error: String?): UUID =
        QuarkusTransaction.requiringNew().call {
            val row = em.createQuery("SELECT i FROM ConnectorInboxItem i WHERE i.connectorId = :c AND i.itemKey = :k", ConnectorInboxItem::class.java)
                .setParameter("c", connectorId).setParameter("k", item.key).resultList.firstOrNull()
                ?: ConnectorInboxItem().apply { this.connectorId = connectorId; itemKey = item.key }.also { em.persist(it) }
            row.name = item.name.take(1024)
            row.size = item.size
            row.status = status
            row.chunks = chunks
            row.records = records
            row.error = error
            if (status == "QUEUED") row.queuedAt = Instant.now()
            em.flush()
            row.id!!
        }

    /** Items read by a connector, newest first, for the connector page. */
    fun recent(connectorId: UUID, limit: Int): List<ConnectorInboxItem> =
        em.createQuery("SELECT i FROM ConnectorInboxItem i WHERE i.connectorId = :c ORDER BY i.firstSeenAt DESC", ConnectorInboxItem::class.java)
            .setParameter("c", connectorId).setMaxResults(limit).resultList

    /** Forgets a FAILED item so the next poll reads it again (after the sender fixed it). */
    fun retry(connectorId: UUID, itemId: UUID): Boolean = QuarkusTransaction.requiringNew().call {
        val row = em.find(ConnectorInboxItem::class.java, itemId)?.takeIf { it.connectorId == connectorId && it.status == "FAILED" }
        row?.let { em.remove(it); true } ?: false
    }
}
