package com.dataturntable.connectors.inbox

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Turns file or attachment bytes into pipeline input. Structured files (CSV, JSON, JSON lines)
 * become records that are split into chunks small enough for one queue message each; other
 * content is passed as text or Base64 in one message.
 */
class InboxParser(
    private val maxChunkBytes: Int = 256 * 1024,
    private val maxChunkRecords: Int = 1000,
    /** Kafka's default message limit is 1 MiB; leave room for the envelope. */
    private val maxSingleBytes: Int = 768 * 1024,
) {
    private val mapper = ObjectMapper()

    /** One queue message: a list of records, or a text/Base64 value. */
    data class Chunk(val payload: Any?, val records: Int?)

    class TooLargeException(message: String) : RuntimeException(message)

    fun parse(content: InboxContent, format: String?, encoding: String?, csvDelimiter: String?): List<Chunk> {
        val charset = encoding?.let { Charset.forName(it) } ?: Charsets.UTF_8
        return when (val f = resolveFormat(format, content)) {
            "csv" -> chunk(parseCsv(decode(content.bytes, charset), csvDelimiter))
            "jsonl" -> chunk(decode(content.bytes, charset).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .mapIndexed { i, line -> runCatching { mapper.readValue(line, Any::class.java) }.getOrElse { throw IllegalArgumentException("Line ${i + 1} is not JSON: ${it.message}") } }
                .toList())
            "json" -> when (val value = mapper.readValue(decode(content.bytes, charset), Any::class.java)) {
                is List<*> -> chunk(value)
                else -> listOf(single(value, content.bytes.size))
            }
            "text" -> listOf(single(decode(content.bytes, charset), content.bytes.size))
            "binary" -> listOf(single(Base64.getEncoder().encodeToString(content.bytes), content.bytes.size * 4 / 3))
            else -> throw IllegalArgumentException("Unknown format '$f' (auto, csv, json, jsonl, text, binary)")
        }
    }

    private fun single(value: Any?, size: Int): Chunk {
        if (size > maxSingleBytes) throw TooLargeException(
            "Content of $size bytes is too large for one message; deliver it as CSV, JSON array or JSON lines to split it into records")
        return Chunk(value, null)
    }

    internal fun resolveFormat(format: String?, content: InboxContent): String {
        val f = format?.trim()?.lowercase()?.ifEmpty { null } ?: "auto"
        if (f != "auto") return f
        val name = content.name.lowercase()
        val type = content.contentType?.lowercase().orEmpty()
        return when {
            name.endsWith(".csv") || type == "text/csv" -> "csv"
            name.endsWith(".jsonl") || name.endsWith(".ndjson") -> "jsonl"
            name.endsWith(".json") || type == "application/json" -> "json"
            isText(content.bytes) -> "text"
            else -> "binary"
        }
    }

    private fun isText(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, minOf(bytes.size, 8192)))
        !bytes.take(8192).contains(0.toByte())
    } catch (_: CharacterCodingException) {
        false
    }

    private fun decode(bytes: ByteArray, charset: Charset): String = String(bytes, charset).removePrefix("﻿")

    private fun chunk(records: List<Any?>): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var current = mutableListOf<Any?>()
        var bytes = 0
        for (record in records) {
            val size = mapper.writeValueAsBytes(record).size + 1
            if (current.isNotEmpty() && (bytes + size > maxChunkBytes || current.size >= maxChunkRecords)) {
                chunks += Chunk(current, current.size)
                current = mutableListOf()
                bytes = 0
            }
            if (size > maxSingleBytes) throw TooLargeException("Record ${chunks.sumOf { it.records ?: 0 } + current.size + 1} has $size bytes, more than one message can hold")
            current += record
            bytes += size
        }
        if (current.isNotEmpty() || chunks.isEmpty()) chunks += Chunk(current, current.size)
        return chunks
    }

    companion object {
        /** RFC 4180 CSV with a header line; the delimiter is detected from the header (`;` is common in German exports). */
        fun parseCsv(text: String, delimiter: String? = null): List<Map<String, String>> {
            val header = text.lineSequence().firstOrNull().orEmpty()
            val sep = delimiter?.let { if (it == "\\t" || it.equals("tab", true)) '\t' else it.first() }
                ?: listOf(';', ',', '\t').maxBy { c -> header.count { it == c } }
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quoted) {
                    when {
                        c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                        c == '"' -> quoted = false
                        else -> field.append(c)
                    }
                } else when (c) {
                    '"' -> quoted = true
                    sep -> { row += field.toString(); field.clear() }
                    '\r' -> {}
                    '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf() }
                    else -> field.append(c)
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
            val nonEmpty = rows.filter { r -> r.any { it.isNotBlank() } }
            if (nonEmpty.isEmpty()) return emptyList()
            val columns = nonEmpty.first().map { it.trim() }
            return nonEmpty.drop(1).map { r -> columns.withIndex().associate { (idx, name) -> name to (r.getOrNull(idx) ?: "") } }
        }
    }
}
