package com.dataturntable.connectors.inbox

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.FlagTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.Properties

/**
 * IMAP mailbox inbox: every e-mail is one item. Config: `host`, `port` (993 / 143),
 * `username`, `password`, `tls` (implicit | starttls | none, default implicit), `folder`
 * (INBOX), `content` (attachments | body, default attachments), `pattern` (glob on attachment
 * names), `afterRead` (seen | move | delete | keep, default seen), `processedFolder`
 * (for move, default `Processed`), `timeoutSeconds` (30).
 *
 * Messages are read with PEEK, so the mailbox changes only in [acknowledge].
 */
class ImapSource(config: Map<String, Any?>) : InboxSource {
    private val pattern = config.str("pattern")
    private val bodyOnly = config.str("content")?.lowercase() == "body"
    private val afterRead = config.str("afterRead")?.lowercase() ?: "seen"
    private val processedFolder = config.str("processedFolder") ?: "Processed"
    private val store: Store
    private val folder: IMAPFolder

    init {
        require(afterRead in setOf("seen", "move", "delete", "keep")) { "afterRead must be seen, move, delete or keep" }
        val host = config.str("host") ?: throw IllegalArgumentException("host is required")
        val tls = config.str("tls")?.lowercase() ?: "implicit"
        val protocol = if (tls == "implicit") "imaps" else "imap"
        val timeout = (config.int("timeoutSeconds", 30) * 1000).toString()
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.peek", "true")
            put("mail.$protocol.connectiontimeout", timeout)
            put("mail.$protocol.timeout", timeout)
            put("mail.$protocol.writetimeout", timeout)
            when (tls) {
                "implicit" -> put("mail.imaps.ssl.checkserveridentity", "true")
                "starttls" -> {
                    put("mail.imap.starttls.enable", "true")
                    put("mail.imap.starttls.required", "true")
                    put("mail.imap.ssl.checkserveridentity", "true")
                }
                "none" -> {}
                else -> throw IllegalArgumentException("tls must be implicit, starttls or none")
            }
        }
        store = Session.getInstance(props).getStore(protocol)
        store.connect(host, config.int("port", if (tls == "implicit") 993 else 143), config.str("username"), config["password"]?.toString())
        try {
            folder = store.getFolder(config.str("folder") ?: "INBOX") as IMAPFolder
            folder.open(if (afterRead == "keep") Folder.READ_ONLY else Folder.READ_WRITE)
        } catch (e: Exception) {
            runCatching { store.close() }
            throw e
        }
    }

    override fun list(): List<InboxItem> {
        val messages = if (afterRead == "seen") folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false)) else folder.messages
        val validity = folder.uidValidity
        return messages.map { m ->
            val uid = folder.getUID(m)
            val messageId = (m as? MimeMessage)?.messageID
            InboxItem(
                key = messageId ?: "uid:$validity:$uid",
                name = m.subject ?: "(no subject)",
                size = m.size.toLong().takeIf { it >= 0 },
                modified = (m.receivedDate ?: m.sentDate)?.toInstant(),
                ref = uid.toString(),
                meta = mapOf(
                    "subject" to m.subject,
                    "from" to m.from?.joinToString { it.toString() },
                    "date" to (m.sentDate ?: m.receivedDate)?.toInstant()?.toString(),
                    "messageId" to messageId,
                ),
            )
        }.sortedWith(compareBy({ it.modified }, { it.ref.toLong() }))
    }

    private fun message(item: InboxItem): Message =
        folder.getMessageByUID(item.ref.toLong()) ?: throw IllegalStateException("Message ${item.key} is no longer in the mailbox")

    override fun read(item: InboxItem): List<InboxContent> {
        val message = message(item)
        if (bodyOnly) {
            val text = textOf(message) ?: ""
            return listOf(InboxContent("body.txt", "text/plain", text.toByteArray()))
        }
        val attachments = mutableListOf<InboxContent>()
        collectAttachments(message, attachments)
        return attachments
    }

    private fun textOf(part: Part): String? = when {
        part.isMimeType("text/plain") -> part.content.toString()
        part.isMimeType("multipart/*") -> (part.content as Multipart).let { mp ->
            (0 until mp.count).firstNotNullOfOrNull { textOf(mp.getBodyPart(it)) }
        }
        else -> null
    }

    private fun collectAttachments(part: Part, out: MutableList<InboxContent>) {
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as Multipart
            for (i in 0 until mp.count) collectAttachments(mp.getBodyPart(i), out)
            return
        }
        val name = part.fileName?.let { MimeUtility.decodeText(it) } ?: return
        if (!Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) && !Part.INLINE.equals(part.disposition, ignoreCase = true) && part.disposition != null) return
        if (!globMatches(pattern, name)) return
        out += InboxContent(name, part.contentType?.substringBefore(';')?.trim(), part.inputStream.use { it.readAllBytes() })
    }

    override fun acknowledge(item: InboxItem) {
        val message = message(item)
        when (afterRead) {
            "keep" -> {}
            "seen" -> message.setFlag(Flags.Flag.SEEN, true)
            "delete" -> {
                message.setFlag(Flags.Flag.DELETED, true)
                folder.expunge(arrayOf(message))
            }
            "move" -> {
                val target = store.getFolder(processedFolder)
                if (!target.exists()) target.create(Folder.HOLDS_MESSAGES)
                message.setFlag(Flags.Flag.SEEN, true)
                folder.moveMessages(arrayOf(message), target)
            }
        }
    }

    override fun close() {
        runCatching { if (folder.isOpen) folder.close(false) }
        runCatching { store.close() }
    }
}
