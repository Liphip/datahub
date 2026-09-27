package com.dataturntable.connectors.inbox

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * FTP / FTPS inbox. Config: `host`, `port` (21), `username`, `password`, `tls`
 * (none | explicit | implicit, default explicit), `path` (remote directory), `pattern`,
 * `minAgeSeconds` (10), `afterRead` (move | delete | keep, default move), `processedDir`
 * (default `.processed` below `path`), `timeoutSeconds` (30).
 */
class FtpSource(config: Map<String, Any?>) : InboxSource {
    private val dir = (config.str("path") ?: "/").trimEnd('/').ifEmpty { "/" }
    private val pattern = config.str("pattern")
    private val minAge = config.int("minAgeSeconds", 10).toLong()
    private val afterRead = AfterRead.parse(config["afterRead"], AfterRead.MOVE)
    private val processedDir = config.str("processedDir")?.let { if (it.startsWith("/")) it else "$dir/$it" } ?: "$dir/.processed"
    private val client: FTPClient

    init {
        val host = config.str("host") ?: throw IllegalArgumentException("host is required")
        val tls = config.str("tls")?.lowercase() ?: "explicit"
        client = when (tls) {
            "none" -> FTPClient()
            "explicit" -> FTPSClient(false)
            "implicit" -> FTPSClient(true)
            else -> throw IllegalArgumentException("tls must be none, explicit or implicit")
        }
        if (client is FTPSClient) {
            // commons-net only checks certificate dates by default: verify chain and host name.
            client.trustManager = TrustManagerUtils.getDefaultTrustManager(null)
            client.setEndpointCheckingEnabled(true)
        }
        val timeout = Duration.ofSeconds(config.int("timeoutSeconds", 30).toLong())
        client.setConnectTimeout(timeout.toMillis().toInt())
        client.setDefaultTimeout(timeout.toMillis().toInt())
        client.setDataTimeout(timeout)
        try {
            client.connect(host, config.int("port", if (tls == "implicit") 990 else 21))
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("FTP server refused connection: ${client.replyString.trim()}")
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            if (!client.login(config.str("username") ?: "anonymous", config["password"]?.toString() ?: "")) {
                throw IOException("FTP login failed: ${client.replyString.trim()}")
            }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.enterLocalPassiveMode()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun remote(name: String) = if (dir == "/") "/$name" else "$dir/$name"

    override fun list(): List<InboxItem> {
        // MLSD gives exact timestamps; old servers only answer LIST.
        val files: Array<FTPFile> = runCatching { client.mlistDir(dir) }.getOrNull()?.takeIf { FTPReply.isPositiveCompletion(client.replyCode) }
            ?: client.listFiles(dir).also { if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("Cannot list $dir: ${client.replyString.trim()}") }
        val cutoff = Instant.now().minusSeconds(minAge)
        return files.filter { it.isFile && !it.name.startsWith(".") && globMatches(pattern, it.name) }
            .map { f ->
                val modified = f.timestamp?.toInstant()
                InboxItem(key = "${remote(f.name)}|${f.size}|${modified?.toEpochMilli() ?: ""}", name = f.name, size = f.size, modified = modified, ref = remote(f.name))
            }
            .filter { it.modified == null || it.modified.isBefore(cutoff) }
            .sortedWith(compareBy({ it.modified }, { it.name }))
    }

    override fun read(item: InboxItem): List<InboxContent> {
        val out = ByteArrayOutputStream()
        if (!client.retrieveFile(item.ref, out)) throw IOException("Cannot read ${item.ref}: ${client.replyString.trim()}")
        return listOf(InboxContent(item.name, null, out.toByteArray()))
    }

    override fun acknowledge(item: InboxItem) {
        when (afterRead) {
            AfterRead.KEEP -> {}
            AfterRead.DELETE -> if (!client.deleteFile(item.ref)) throw IOException("Cannot delete ${item.ref}: ${client.replyString.trim()}")
            AfterRead.MOVE -> {
                client.makeDirectory(processedDir) // fails harmlessly when it exists
                var target = "$processedDir/${item.name}"
                if (client.listFiles(target).isNotEmpty()) target += ".${Instant.now().toEpochMilli()}"
                if (!client.rename(item.ref, target)) throw IOException("Cannot move ${item.ref} to $target: ${client.replyString.trim()}")
            }
        }
    }

    override fun close() {
        runCatching { if (client.isConnected) { client.logout(); client.disconnect() } }
    }
}
