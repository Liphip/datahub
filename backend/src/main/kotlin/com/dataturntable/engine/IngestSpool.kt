package com.dataturntable.engine

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable fallback for records Kafka cannot take right now.
 *
 * Many senders are fire-and-forget: they never read the response and never resend, so a
 * refused record would be lost. When the Kafka write fails, the record is appended to a
 * local spool file and fsync'd, and the sender still gets its acknowledgement. A replayer
 * forwards spooled records as soon as Kafka accepts writes again. While the spool is not
 * empty every new record goes through it too, so records keep their order.
 *
 * A spooled record keeps its run id, so a record that did reach Kafka although the send
 * reported a failure is recognised as the same run and processed once.
 *
 * On Kubernetes the spool directory is a persistent volume of the ingest pod; a pod that
 * restarts replays what it spooled. On shutdown the pod waits (up to its grace period) for
 * the spool to drain.
 */
@ApplicationScoped
class IngestSpool {

    data class Entry(val key: String, val message: String)

    private val log = Logger.getLogger(IngestSpool::class.java)
    private val objectMapper = ObjectMapper()
    private val lock = ReentrantLock()
    private val pending = AtomicLong()

    @ConfigProperty(name = "dataturntable.ingest.spool-dir")
    lateinit var spoolDir: String

    /** A segment is closed for writing at this size and replayed as a whole. */
    @ConfigProperty(name = "dataturntable.ingest.spool-segment-bytes", defaultValue = "16777216")
    var segmentBytes: Long = 16L * 1024 * 1024

    /** Refuse new records (503) when the spool is this large: the disk must not fill up. */
    @ConfigProperty(name = "dataturntable.ingest.spool-max-bytes", defaultValue = "10737418240")
    var maxBytes: Long = 10L * 1024 * 1024 * 1024

    @ConfigProperty(name = "dataturntable.ingest.spool-drain-timeout", defaultValue = "25s")
    lateinit var drainTimeout: Duration

    @Inject
    lateinit var meterRegistry: MeterRegistry

    private val segmentCounter = AtomicLong()
    private var current: FileChannel? = null
    private var currentFile: File? = null

    @PostConstruct
    fun init() {
        dir().mkdirs()
        pending.set(segments().sumOf { countLines(it) })
        meterRegistry.gauge("dataturntable_ingest_spool_records", pending)
        if (pending.get() > 0) log.warnf("Ingest spool holds %d records from before the restart; replaying", pending.get())
    }

    fun isEmpty(): Boolean = pending.get() == 0L

    fun size(): Long = pending.get()

    /** Appends and fsyncs; returns only when the record is on disk. */
    fun append(entry: Entry) = lock.withLock {
        if (totalBytes() >= maxBytes) throw IllegalStateException("Ingest spool is full")
        val channel = current?.takeIf { it.size() < segmentBytes } ?: openSegment()
        val line = objectMapper.writeValueAsString(mapOf("k" to entry.key, "m" to entry.message)) + "\n"
        channel.write(ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8)))
        channel.force(false)
        pending.incrementAndGet()
    }

    /**
     * Forwards spooled records with [send] (which must throw unless Kafka acknowledged),
     * segment by segment in order. A segment is deleted only when all its records were sent.
     * Returns the number of records forwarded.
     */
    fun replay(send: (Entry) -> Unit): Int {
        var sent = 0
        for (segment in segments()) {
            // Close the segment being written, so later records start a new one.
            lock.withLock {
                if (currentFile == segment) {
                    current?.close(); current = null; currentFile = null
                }
            }
            val lines = segment.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            val progress = File(segment.path + ".done")
            val alreadySent = if (progress.exists()) progress.readText().trim().toIntOrNull() ?: 0 else 0
            for ((index, line) in lines.withIndex()) {
                if (index < alreadySent) continue
                val node = objectMapper.readTree(line)
                send(Entry(node.path("k").asText(), node.path("m").asText()))
                // Durable progress before the in-memory count changes, not after (and not just
                // every 500th record): if send() throws on a later line in this same segment —
                // Kafka accepted a prefix, then refused again — the next replay attempt resumes
                // past what was actually sent. Checkpointing only every 500 records left a gap
                // where an interrupted segment shorter than that resent (and re-decremented)
                // already-sent records on every retry, eventually driving `pending` to zero
                // while real, unsent records remained on disk — isEmpty() then made
                // scheduledReplay() return early forever, permanently stranding those records.
                progress.writeText((index + 1).toString())
                sent++
                pending.decrementAndGet()
            }
            segment.delete()
            progress.delete()
        }
        return sent
    }

    @Scheduled(every = "\${dataturntable.ingest.spool-replay-interval:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun scheduledReplay() {
        if (isEmpty()) return
        try {
            val n = replayer?.invoke() ?: 0
            if (n > 0) log.infof("Forwarded %d spooled records to Kafka (%d left)", n, pending.get())
        } catch (e: Exception) {
            log.debugf("Spool replay paused, Kafka still unavailable: %s", e.message)
        }
    }

    /** Set by [PipelineRunQueue]; kept as a callback to avoid a dependency cycle. */
    @Volatile
    var replayer: (() -> Int)? = null

    fun onStop(@Observes event: ShutdownEvent) {
        val deadline = System.nanoTime() + drainTimeout.toNanos()
        while (!isEmpty() && System.nanoTime() < deadline) {
            runCatching { replayer?.invoke() }
            if (!isEmpty()) Thread.sleep(500)
        }
        if (!isEmpty()) log.warnf("Shutting down with %d spooled records; they are replayed after the restart", pending.get())
        lock.withLock { current?.close() }
    }

    private fun dir() = File(spoolDir)

    private fun segments(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.startsWith("segment-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name }.orEmpty()

    private fun totalBytes(): Long = segments().sumOf { it.length() }

    private fun countLines(file: File): Long {
        val done = File(file.path + ".done").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0
        return file.useLines { lines -> lines.count { it.isNotBlank() }.toLong() } - done
    }

    private fun openSegment(): FileChannel {
        current?.close()
        // Names sort in creation order, also across restarts.
        val file = File(dir(), "segment-%015d-%06d.log".format(System.currentTimeMillis(), segmentCounter.incrementAndGet() % 1_000_000))
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        current = channel
        currentFile = file
        return channel
    }
}
