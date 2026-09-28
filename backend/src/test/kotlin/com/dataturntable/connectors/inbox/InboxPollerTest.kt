package com.dataturntable.connectors.inbox

import com.dataturntable.connectors.Connector
import com.dataturntable.connectors.polling.PollTransactionExecutor
import com.dataturntable.engine.PipelineDirectory
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import com.sun.net.httpserver.HttpServer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.activation.DataHandler
import jakarta.inject.Inject
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import jakarta.persistence.EntityManager
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Properties
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyBean

@QuarkusTest
class InboxPollerTest {

    @Inject lateinit var poller: InboxPoller
    @Inject lateinit var restPoll: PollTransactionExecutor
    @Inject lateinit var leases: com.dataturntable.connectors.ConnectorLeaseService
    @Inject lateinit var directory: PipelineDirectory
    @Inject lateinit var em: EntityManager
    @Inject @AnyBean lateinit var messaging: InMemoryConnector

    private val mapper = ObjectMapper()
    private lateinit var tenantId: UUID
    private val pipelines = mutableListOf<UUID>()
    private val base: Path = Path.of("target/test-files").toAbsolutePath()
    private lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        tenantId = QuarkusTransaction.requiringNew().call { Tenant().apply { name = "inbox-${UUID.randomUUID()}" }.also { it.persist() }.id!! }
        dir = base.resolve("inbox-${UUID.randomUUID()}")
        Files.createDirectories(dir)
        messaging.sink<String>("pipeline-commands").clear()
    }

    @AfterEach
    fun cleanUp() {
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM PipelineRun r WHERE r.pipeline.id IN :p").setParameter("p", pipelines + UUID.randomUUID()).executeUpdate()
            em.createQuery("DELETE FROM Pipeline p WHERE p.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
            em.createQuery("DELETE FROM ConnectorInboxItem i WHERE i.connectorId IN (SELECT c.id FROM Connector c WHERE c.tenant.id = :t)").setParameter("t", tenantId).executeUpdate()
            em.createQuery("DELETE FROM Connector c WHERE c.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
            em.createQuery("DELETE FROM Tenant t WHERE t.id = :t").setParameter("t", tenantId).executeUpdate()
        }
        pipelines.clear()
        directory.refresh()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun connector(type: Connector.ConnectorType, config: Map<String, Any?>): UUID = QuarkusTransaction.requiringNew().call {
        val connectorId = Connector().apply {
            tenant = em.find(Tenant::class.java, tenantId)
            name = "$type inbox"
            connectorType = type
            direction = Connector.Direction.INPUT
            this.config = mapper.writeValueAsString(config)
            pollingEnabled = true
        }.also { em.persist(it) }.id!!
        pipelines += Pipeline().apply {
            tenant = em.find(Tenant::class.java, tenantId)
            name = "reads $type"
            isActive = true
            graphDefinition = mapper.writeValueAsString(mapOf("nodes" to listOf(mapOf("id" to "in", "type" to "IMPORT", "connectorId" to connectorId.toString())), "edges" to emptyList<Any>()))
        }.also { em.persist(it) }.id!!
        connectorId
    }.also { directory.refresh() }

    private fun setConfig(connectorId: UUID, config: Map<String, Any?>) = QuarkusTransaction.requiringNew().run {
        em.find(Connector::class.java, connectorId).config = mapper.writeValueAsString(config)
    }

    /** Messages queued since the last call: (runId, input). */
    private fun drain(): List<Pair<String, Map<*, *>>> {
        val sink = messaging.sink<String>("pipeline-commands")
        return sink.received().map { m ->
            val msg = mapper.readValue(m.payload, Map::class.java)
            msg["runId"].toString() to (msg["inputData"] ?: msg["input"] ?: msg["data"]) as Map<*, *>
        }.also { sink.clear() }
    }

    private fun file(name: String, content: String): Path = dir.resolve(name).also {
        Files.writeString(it, content)
        Files.setLastModifiedTime(it, FileTime.from(Instant.now().minusSeconds(60)))
    }

    private fun status(connectorId: UUID) = QuarkusTransaction.requiringNew().call {
        em.createQuery("SELECT i.status FROM ConnectorInboxItem i WHERE i.connectorId = :c", String::class.java).setParameter("c", connectorId).resultList
    }

    private val csv = "EQUNR;SERNR;HERST\n10000001;1ESY1;EMH\n10000002;1ESY2;\"Landis+Gyr\"\n10000003;1ESY3;EMH\n"

    // ── Directory ───────────────────────────────────────────────────────────

    @Test
    fun fileIsQueuedOnceAndMovedAway() {
        val id = connector(Connector.ConnectorType.FILE, mapOf("path" to base.relativize(dir).toString()))
        file("meters.csv", csv)
        file("fresh.csv", csv).also { Files.setLastModifiedTime(it, FileTime.from(Instant.now())) } // still being written

        val result = poller.poll(id)
        assertEquals(1, result.queued)
        val (_, input) = drain().single()
        val records = input["payload"] as List<*>
        assertEquals(3, records.size)
        assertEquals("Landis+Gyr", (records[1] as Map<*, *>)["HERST"])
        assertEquals("meters.csv", (input["_source"] as Map<*, *>)["item"])
        assertFalse(Files.exists(dir.resolve("meters.csv")))
        assertTrue(Files.exists(dir.resolve(".processed/meters.csv")))
        assertTrue(Files.exists(dir.resolve("fresh.csv")))

        assertEquals(0, poller.poll(id).queued)
        assertTrue(drain().isEmpty())
        assertEquals(listOf("DONE"), status(id))
    }

    @Test
    fun anInterruptedPollNeitherLosesNorDuplicates() {
        // The processed "directory" is a file: acknowledging (moving) fails after queueing.
        Files.writeString(dir.resolve("blocked"), "")
        val cfg = mapOf("path" to base.relativize(dir).toString(), "processedDir" to "blocked")
        val id = connector(Connector.ConnectorType.FILE, cfg)
        file("meters.csv", csv)

        assertThrows(Exception::class.java) { poller.poll(id) }
        assertEquals(1, drain().size)
        assertEquals(listOf("QUEUED"), status(id))
        assertTrue(Files.exists(dir.resolve("meters.csv")), "the file stays until it is acknowledged")

        // Next poll after the fix: acknowledged without queueing again.
        setConfig(id, cfg - "processedDir")
        poller.poll(id)
        assertTrue(drain().isEmpty())
        assertEquals(listOf("DONE"), status(id))

    }

    @Test
    fun theSameItemAlwaysGetsTheSameRunId() {
        val id = connector(Connector.ConnectorType.FILE, mapOf("path" to base.relativize(dir).toString(), "afterRead" to "keep"))
        val f = file("meters.csv", csv)
        poller.poll(id)
        val first = drain().single().first
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM ConnectorInboxItem i WHERE i.connectorId = :c").setParameter("c", id).executeUpdate()
        }
        assertTrue(Files.exists(f))
        poller.poll(id)
        assertEquals(first, drain().single().first)
        // Kept files are not read again while the ledger knows them.
        poller.poll(id)
        assertTrue(drain().isEmpty())
    }

    @Test
    fun brokenFilesAreRecordedAndLeftForRetry() {
        val id = connector(Connector.ConnectorType.FILE, mapOf("path" to base.relativize(dir).toString()))
        file("broken.json", "{not json")
        val result = poller.poll(id)
        assertEquals(1, result.failed)
        assertTrue(drain().isEmpty())
        assertEquals(listOf("FAILED"), status(id))
        assertTrue(Files.exists(dir.resolve("broken.json")))
        assertEquals(1, poller.poll(id).skipped)

        val itemId = QuarkusTransaction.requiringNew().call { poller.recent(id, 10).single().id!! }
        file("broken.json", "[{\"a\": 1}]")
        assertTrue(poller.retry(id, itemId))
        assertEquals(1, poller.poll(id).queued)
    }

    @Test
    fun largeFilesAreSplitIntoChunks() {
        val id = connector(Connector.ConnectorType.FILE, mapOf("path" to base.relativize(dir).toString()))
        file("big.csv", "id;value\n" + (1..2500).joinToString("\n") { "$it;${it * 2}" })
        poller.poll(id)
        val chunks = drain()
        assertEquals(3, chunks.size)
        assertEquals(2500, chunks.sumOf { (it.second["payload"] as List<*>).size })
        assertEquals(3, chunks.map { it.first }.toSet().size)
    }

    @Test
    fun pathsOutsideTheBaseDirectoryAreRefused() {
        val id = connector(Connector.ConnectorType.FILE, mapOf("path" to "../../"))
        val e = assertThrows(IllegalArgumentException::class.java) { poller.poll(id) }
        assertTrue(e.message!!.contains("outside"), e.message)
    }

    @Test
    fun nothingIsReadWithoutAPipeline() {
        val id = connector(Connector.ConnectorType.FILE, mapOf("path" to base.relativize(dir).toString()))
        QuarkusTransaction.requiringNew().run { em.find(Pipeline::class.java, pipelines.single()).isActive = false }
        directory.refresh()
        file("meters.csv", csv)
        assertEquals(0, poller.poll(id).queued)
        assertTrue(Files.exists(dir.resolve("meters.csv")))
    }

    // ── FTP ─────────────────────────────────────────────────────────────────

    @Test
    fun ftpFilesAreQueuedAndMoved() {
        val home = Files.createDirectories(dir.resolve("ftp-home/in"))
        Files.writeString(home.resolve("meters.csv"), csv)
        Files.setLastModifiedTime(home.resolve("meters.csv"), FileTime.from(Instant.now().minusSeconds(3600)))
        val factory = FtpServerFactory()
        val listener = ListenerFactory().apply { port = 0 }
        factory.addListener("default", listener.createListener())
        factory.userManager.save(BaseUser().apply {
            name = "sap"; password = "secret"; homeDirectory = dir.resolve("ftp-home").toString()
            authorities = listOf<Authority>(WritePermission())
        })
        val server = factory.createServer()
        server.start()
        try {
            val port = factory.getListener("default").port
            val id = connector(Connector.ConnectorType.FTP, mapOf("host" to "127.0.0.1", "port" to port, "tls" to "none",
                "username" to "sap", "password" to "secret", "path" to "/in", "minAgeSeconds" to 0))
            assertEquals(1, poller.poll(id).queued)
            assertEquals(3, (drain().single().second["payload"] as List<*>).size)
            assertFalse(Files.exists(home.resolve("meters.csv")))
            assertTrue(Files.exists(home.resolve(".processed/meters.csv")))
        } finally {
            server.stop()
        }
    }

    // ── SFTP ────────────────────────────────────────────────────────────────

    @Test
    fun sftpNeedsThePinnedHostKey() {
        val root = Files.createDirectories(dir.resolve("sftp-root/upload"))
        Files.writeString(root.resolve("meters.csv"), csv)
        Files.setLastModifiedTime(root.resolve("meters.csv"), FileTime.from(Instant.now().minusSeconds(3600)))
        val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser"))
            setPasswordAuthenticator { user, pass, _ -> user == "sap" && pass == "secret" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(dir.resolve("sftp-root"))
        }
        server.start()
        try {
            val cfg = mapOf("host" to "127.0.0.1", "port" to server.port, "username" to "sap", "password" to "secret", "path" to "/upload", "minAgeSeconds" to 0)
            val id = connector(Connector.ConnectorType.SFTP, cfg)
            val refused = assertThrows(Exception::class.java) { poller.poll(id) }
            val fingerprint = Regex("SHA256:\\S+").find(refused.message!!)?.value
            assertTrue(fingerprint != null, refused.message)
            assertTrue(drain().isEmpty())

            setConfig(id, cfg + ("hostKeyFingerprint" to fingerprint))
            assertEquals(1, poller.poll(id).queued)
            assertEquals(3, (drain().single().second["payload"] as List<*>).size)
            assertTrue(Files.exists(root.resolve(".processed/meters.csv")))

            setConfig(id, cfg + ("hostKeyFingerprint" to "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
            val mismatch = assertThrows(Exception::class.java) { poller.poll(id) }
            assertTrue(mismatch.message!!.contains("mismatch"), mismatch.message)
        } finally {
            server.stop(true)
        }
    }

    // ── IMAP ────────────────────────────────────────────────────────────────

    @Test
    fun mailAttachmentsAreQueuedAndTheMailMarkedSeen() {
        val mail = GreenMail(ServerSetupTest.SMTP_IMAP)
        mail.start()
        try {
            val user = mail.setUser("readings@example.com", "readings", "secret")
            val session = Session.getInstance(Properties())
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress("sap@example.com"))
                setRecipient(Message.RecipientType.TO, InternetAddress("readings@example.com"))
                subject = "Zählerstammdaten"
                setContent(MimeMultipart().apply {
                    addBodyPart(MimeBodyPart().apply { setText("Anbei die Stammdaten.") })
                    addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(csv, "text/csv"))
                        fileName = "meters.csv"
                        disposition = MimeBodyPart.ATTACHMENT
                    })
                    addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(1, 2, 3), "image/png"))
                        fileName = "logo.png"
                        disposition = MimeBodyPart.ATTACHMENT
                    })
                })
            }
            user.deliver(message)

            val id = connector(Connector.ConnectorType.EMAIL, mapOf("host" to "127.0.0.1", "port" to mail.imap.port, "tls" to "none",
                "username" to "readings", "password" to "secret", "pattern" to "*.csv"))
            assertEquals(1, poller.poll(id).queued)
            val (_, input) = drain().single()
            assertEquals(3, (input["payload"] as List<*>).size)
            val source = input["_source"] as Map<*, *>
            assertEquals("Zählerstammdaten", source["subject"])
            assertEquals("meters.csv", source["part"])

            assertEquals(0, poller.poll(id).queued)
            assertTrue(drain().isEmpty())
            val store = session.getStore("imap").apply { connect("127.0.0.1", mail.imap.port, "readings", "secret") }
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
            assertTrue(inbox.messages.single().flags.contains(Flags.Flag.SEEN))
            store.close()
        } finally {
            mail.stop()
        }
    }

    // ── REST poll ───────────────────────────────────────────────────────────

    @Test
    fun polledRecordsAreDeduplicatedByTheirFields() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/readings") { ex ->
            val body = """[{"meter":"A","ts":"2026-09-27T10:00:00Z","kwh":1},{"meter":"B","ts":"2026-09-27T10:00:00Z","kwh":2}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val id = QuarkusTransaction.requiringNew().call {
                Connector().apply {
                    tenant = em.find(Tenant::class.java, tenantId); name = "REST poll"; connectorType = Connector.ConnectorType.REST
                    direction = Connector.Direction.INPUT; pollingEnabled = true
                    config = mapper.writeValueAsString(mapOf("baseUrl" to "http://127.0.0.1:${server.address.port}", "path" to "/readings"))
                }.also { em.persist(it) }.id!!.also { cid ->
                    pipelines += Pipeline().apply {
                        tenant = em.find(Tenant::class.java, tenantId); name = "REST readings"; isActive = true
                        graphDefinition = mapper.writeValueAsString(mapOf("nodes" to listOf(mapOf("id" to "in", "type" to "IMPORT",
                            "connectorId" to cid.toString(), "deduplicateBy" to listOf("meter", "ts"))), "edges" to emptyList<Any>()))
                    }.also { em.persist(it) }.id!!
                }
            }
            directory.refresh()
            val token = leases.acquireOrRenew(id)!!.fencingToken
            restPoll.executePoll(id, token)
            val first = drain()
            assertEquals(2, first.size)
            restPoll.executePoll(id, token)
            assertEquals(first.map { it.first }.toSet(), drain().map { it.first }.toSet(), "the same records get the same run ids")
        } finally {
            server.stop(0)
        }
    }
}
