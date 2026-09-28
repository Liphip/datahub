package com.dataturntable.connectors.inbox

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * SFTP inbox. Config: `host`, `port` (22), `username`, `password` and/or `privateKey`
 * (OpenSSH or PEM text) with `passphrase`, `hostKeyFingerprint` (`SHA256:...`, required:
 * the error names the server's fingerprint), `path`, `pattern`, `minAgeSeconds` (10),
 * `afterRead` (move | delete | keep, default move), `processedDir` (default `.processed`),
 * `timeoutSeconds` (30).
 */
class SftpSource(config: Map<String, Any?>) : InboxSource {
    private val dir = (config.str("path") ?: ".").trimEnd('/').ifEmpty { "/" }
    private val pattern = config.str("pattern")
    private val minAge = config.int("minAgeSeconds", 10).toLong()
    private val afterRead = AfterRead.parse(config["afterRead"], AfterRead.MOVE)
    private val processedDir = config.str("processedDir")?.let { if (it.startsWith("/")) it else "$dir/$it" } ?: "$dir/.processed"
    private val client: SshClient = SshClient.setUpDefaultClient()
    private val session: ClientSession
    private val sftp: SftpClient

    init {
        val host = config.str("host") ?: throw IllegalArgumentException("host is required")
        val username = config.str("username") ?: throw IllegalArgumentException("username is required")
        val expected = config.str("hostKeyFingerprint")?.let { "SHA256:" + it.removePrefix("SHA256:") }
        val timeout = Duration.ofSeconds(config.int("timeoutSeconds", 30).toLong())
        var presented: String? = null
        client.serverKeyVerifier = ServerKeyVerifier { _, _, key ->
            presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)
            expected != null && presented == expected
        }
        // Only the credentials of this connector: no ~/.ssh keys or config of the server.
        client.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
        client.hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
        client.start()
        try {
            try {
                session = client.connect(username, host, config.int("port", 22)).verify(timeout).session
                config["password"]?.toString()?.takeIf { it.isNotEmpty() }?.let { session.addPasswordIdentity(it) }
                config.str("privateKey")?.let { pem ->
                    val passphrase = config["passphrase"]?.toString()
                    SecurityUtils.loadKeyPairIdentities(session, NamedResource.ofName("connector-key"), pem.byteInputStream(),
                        passphrase?.let { FilePasswordProvider.of(it) } ?: FilePasswordProvider.EMPTY)
                        ?.forEach { session.addPublicKeyIdentity(it) }
                }
                session.auth().verify(timeout)
            } catch (e: Exception) {
                val shown = presented
                if (shown != null && shown != expected) {
                    throw IOException(
                        if (expected == null) "Set hostKeyFingerprint to trust this server. It presented $shown"
                        else "Host key mismatch: server presented $shown, expected $expected", e)
                }
                throw IOException("SFTP connection to $host failed: ${e.message}", e)
            }
            sftp = SftpClientFactory.instance().createSftpClient(session)
        } catch (e: Exception) {
            client.stop()
            throw e
        }
    }

    private fun remote(name: String) = if (dir == "/") "/$name" else "$dir/$name"

    override fun list(): List<InboxItem> {
        val cutoff = Instant.now().minusSeconds(minAge)
        return sftp.readDir(dir)
            .filter { it.attributes.isRegularFile && !it.filename.startsWith(".") && globMatches(pattern, it.filename) }
            .map { e ->
                val modified = e.attributes.modifyTime?.toInstant()
                InboxItem(key = "${remote(e.filename)}|${e.attributes.size}|${modified?.toEpochMilli() ?: ""}",
                    name = e.filename, size = e.attributes.size, modified = modified, ref = remote(e.filename))
            }
            .filter { it.modified == null || it.modified.isBefore(cutoff) }
            .sortedWith(compareBy({ it.modified }, { it.name }))
    }

    override fun read(item: InboxItem): List<InboxContent> =
        listOf(InboxContent(item.name, null, sftp.read(item.ref).use { it.readAllBytes() }))

    private fun exists(path: String) = try {
        sftp.stat(path); true
    } catch (e: SftpException) {
        if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    override fun acknowledge(item: InboxItem) {
        when (afterRead) {
            AfterRead.KEEP -> {}
            AfterRead.DELETE -> sftp.remove(item.ref)
            AfterRead.MOVE -> {
                if (!exists(processedDir)) sftp.mkdir(processedDir)
                var target = "$processedDir/${item.name}"
                if (exists(target)) target += ".${Instant.now().toEpochMilli()}"
                sftp.rename(item.ref, target)
            }
        }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { session.close() }
        runCatching { client.stop() }
    }
}
