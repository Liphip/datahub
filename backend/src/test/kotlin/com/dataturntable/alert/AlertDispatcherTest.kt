package com.dataturntable.alert

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.sun.net.httpserver.HttpServer
import io.quarkus.mailer.MockMailbox
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
class AlertDispatcherTest {

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var mailbox: MockMailbox

    private lateinit var server: HttpServer
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val createdTenants = mutableListOf<UUID>()

    @BeforeEach
    fun startWebhookReceiver() {
        received.clear()
        mailbox.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { exchange ->
                received += exchange.requestBody.readAllBytes().decodeToString()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
    }

    @AfterEach
    fun cleanUp() {
        server.stop(0)
        QuarkusTransaction.requiringNew().run {
            createdTenants.forEach { tenantId ->
                listOf("AlertHistory", "AlertChannel", "Pipeline").forEach { entity ->
                    em.createQuery("DELETE FROM $entity e WHERE e.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                }
                em.createQuery("DELETE FROM Tenant t WHERE t.id = :t").setParameter("t", tenantId).executeUpdate()
            }
        }
        createdTenants.clear()
    }

    private data class Fixture(val pipelineId: UUID, val channelId: UUID)

    private fun fixture(type: AlertChannelType, config: String): Fixture = QuarkusTransaction.requiringNew().call {
        val tenant = Tenant().apply { name = "alert-tenant-${UUID.randomUUID()}" }.also { it.persist() }
        createdTenants += tenant.id!!
        val pipeline = Pipeline().apply { this.tenant = tenant; name = "Boiler" }.also { it.persist() }
        val channel = AlertChannel().apply {
            this.tenant = tenant
            name = "ops"
            channelType = type
            this.config = config
        }.also { it.persist() }
        Fixture(pipeline.id!!, channel.id!!)
    }

    private fun dispatch(f: Fixture, title: String = "Too hot", cooldown: Long? = null, dedupKey: String? = "k") =
        QuarkusTransaction.requiringNew().run {
            alertDispatcher.dispatchToChannel(
                pipeline = em.find(Pipeline::class.java, f.pipelineId),
                channelRef = f.channelId.toString(),
                title = title,
                message = "value 91",
                severity = "critical",
                payload = mapOf("value" to 91),
                dedupKey = dedupKey,
                cooldownSeconds = cooldown,
            )
        }

    private fun history(f: Fixture): List<AlertHistory> = QuarkusTransaction.requiringNew().call {
        em.createQuery("SELECT a FROM AlertHistory a WHERE a.channel.id = :c", AlertHistory::class.java)
            .setParameter("c", f.channelId).resultList
    }

    @Test
    fun webhookChannelReceivesAlert() {
        val f = fixture(AlertChannelType.WEBHOOK, """{"url":"http://127.0.0.1:${server.address.port}/hook"}""")

        dispatch(f)

        assertEquals(1, received.size)
        assertTrue(received.single().contains("\"title\":\"Too hot\""), received.single())
        assertTrue(received.single().contains("\"severity\":\"CRITICAL\""), received.single())
        assertTrue(received.single().contains("\"payload\":{\"value\":91}"), received.single())
        assertEquals(AlertStatus.SENT, history(f).single().status)
    }

    @Test
    fun repeatWithinCooldownIsSuppressed() {
        val f = fixture(AlertChannelType.WEBHOOK, """{"url":"http://127.0.0.1:${server.address.port}/hook"}""")

        dispatch(f, cooldown = 300)
        dispatch(f, cooldown = 300)
        dispatch(f, title = "Other alert", cooldown = 300, dedupKey = "other")

        assertEquals(2, received.size)
        assertEquals(2, history(f).size)
    }

    @Test
    fun zeroCooldownSendsEveryAlert() {
        val f = fixture(AlertChannelType.WEBHOOK, """{"url":"http://127.0.0.1:${server.address.port}/hook"}""")

        dispatch(f, cooldown = 0)
        dispatch(f, cooldown = 0)

        assertEquals(2, received.size)
    }

    @Test
    fun emailChannelSendsToEveryRecipient() {
        val f = fixture(AlertChannelType.EMAIL, """{"recipient":"ops@example.com, oncall@example.com"}""")

        dispatch(f)

        assertEquals(1, mailbox.getMailsSentTo("ops@example.com").size)
        assertEquals(1, mailbox.getMailsSentTo("oncall@example.com").size)
        assertEquals("[CRITICAL] Too hot", mailbox.getMailsSentTo("ops@example.com").single().subject)
        assertEquals(AlertStatus.SENT, history(f).single().status)
    }

    @Test
    fun failedDeliveryIsRecordedAsFailed() {
        val f = fixture(AlertChannelType.WEBHOOK, """{"url":"http://127.0.0.1:${server.address.port}/missing"}""")

        dispatch(f)

        assertEquals(AlertStatus.FAILED, history(f).single().status)
    }
}
