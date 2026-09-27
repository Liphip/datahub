package com.dataturntable.connectors.inbox

import java.io.Closeable
import java.time.Instant

/**
 * An item waiting in a pull source: a file in a directory or on an FTP/SFTP server, or an
 * e-mail. [key] identifies the item for the ledger; the same item must always get the same key.
 */
data class InboxItem(
    val key: String,
    val name: String,
    val size: Long?,
    val modified: Instant?,
    /** Source-specific handle, e.g. the full remote path or the IMAP UID. */
    val ref: String,
    /** Extra fields passed to the pipeline as `_source`, e.g. subject and sender of an e-mail. */
    val meta: Map<String, Any?> = emptyMap(),
)

/** A part of an item that becomes pipeline input: the file itself, or one e-mail attachment. */
data class InboxContent(val name: String, val contentType: String?, val bytes: ByteArray, val meta: Map<String, Any?> = emptyMap())

/**
 * What happens to an item at the source after its data is safely in the pipeline queue.
 * [KEEP] leaves it (read-only sources); the ledger then prevents reading it twice.
 */
enum class AfterRead { MOVE, DELETE, KEEP;
    companion object {
        fun parse(raw: Any?, default: AfterRead): AfterRead =
            raw?.toString()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { v ->
                entries.firstOrNull { it.name == v } ?: throw IllegalArgumentException("afterRead must be move, delete or keep")
            } ?: default
    }
}

/**
 * A pull source. An instance is opened for one poll and closed afterwards.
 * Implementations must not change the source in [list] or [read].
 */
interface InboxSource : Closeable {
    /** Items ready to be read, oldest first. */
    fun list(): List<InboxItem>

    /** The data of an item. An e-mail yields its attachments or its body. */
    fun read(item: InboxItem): List<InboxContent>

    /** Moves, deletes or flags the item, as configured. Called only after the data is queued. */
    fun acknowledge(item: InboxItem)

    override fun close() {}
}

/** Glob match on a file name (`*.csv`, `meter_*.json`), case-insensitive. */
internal fun globMatches(pattern: String?, name: String): Boolean {
    val glob = pattern?.trim().takeUnless { it.isNullOrEmpty() } ?: return true
    return glob.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.any { g ->
        val regex = buildString {
            g.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        Regex(regex, RegexOption.IGNORE_CASE).matches(name)
    }
}

internal fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()?.trim()?.ifEmpty { null }

internal fun Map<String, Any?>.int(key: String, default: Int): Int =
    when (val v = this[key]) {
        is Number -> v.toInt()
        null -> default
        else -> v.toString().trim().toIntOrNull() ?: default
    }

internal fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean =
    when (val v = this[key]) {
        is Boolean -> v
        null -> default
        else -> v.toString().trim().lowercase() in setOf("true", "1", "yes")
    }
