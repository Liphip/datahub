package com.dataturntable.alert

import com.dataturntable.events.ConsumerDeadLetter
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.tenant.Tenant
import io.quarkus.mailer.MockMailbox
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The four ALT-13 conditions, and that raising them reaches the tenant and the administrators
 * exactly once per window. The findings are checked directly rather than through the scheduler,
 * which never runs in tests (`background-enabled: false`).
 */
@QuarkusTest
class PlatformHealthMonitorTest {

    @Inject
    lateinit var monitor: PlatformHealthMonitor

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var mailbox: MockMailbox

    private val tenantIds = mutableListOf<UUID>()
    private val deadLetterIds = mutableListOf<UUID>()

    private var failureThreshold = 3
    private var failureWindowSeconds = 900L
    private var lagThresholdSeconds = 300L
    private var dedupeWindowSeconds = 3600L
    
    @BeforeEach
    fun setUp() {
        mailbox.clear()
        monitor.failureThreshold = failureThreshold
        monitor.failureWindowSeconds = failureWindowSeconds
        monitor.lagThresholdSeconds = lagThresholdSeconds
        monitor.dedupeWindowSeconds = dedupeWindowSeconds
        monitor.adminEmails = Optional.empty()
    }

    @AfterEach
    fun tearDown() {
        monitor.failureThreshold = failureThreshold
        monitor.failureWindowSeconds = failureWindowSeconds
        monitor.lagThresholdSeconds = lagThresholdSeconds
        monitor.dedupeWindowSeconds = dedupeWindowSeconds
        monitor.adminEmails = Optional.empty()
        QuarkusTransaction.requiringNew().run {
            tenantIds.forEach { tenantId ->
                em.createQuery("DELETE FROM AlertHistory a WHERE a.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                em.createQuery("DELETE FROM AlertChannel c WHERE c.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                em.createQuery("DELETE FROM PipelineRun r WHERE r.pipeline.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                em.createQuery("DELETE FROM Pipeline p WHERE p.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                em.createQuery("DELETE FROM Tenant t WHERE t.id = :t").setParameter("t", tenantId).executeUpdate()
            }
            deadLetterIds.forEach { id ->
                em.createQuery("DELETE FROM ConsumerDeadLetter d WHERE d.id = :id").setParameter("id", id).executeUpdate()
            }
        }
        tenantIds.clear()
        deadLetterIds.clear()
    }

    /** A tenant with an active pipeline and one active email channel, which is then its default. */
    private fun fixture(pipelineName: String = "Nightly"): UUID = QuarkusTransaction.requiringNew().call {
        val tenant = Tenant().apply { name = "health-tenant-${UUID.randomUUID()}" }.also { it.persist() }
        tenantIds += tenant.id!!
        Pipeline().apply {
            this.tenant = tenant
            name = pipelineName
            isActive = true
        }.also { it.persist() }
        AlertChannel().apply {
            this.tenant = tenant
            name = "ops"
            channelType = AlertChannelType.EMAIL
            config = """{"recipient":"ops@example.com"}"""
        }.also { it.persist() }
        tenant.id!!
    }

    private fun run(tenantId: UUID, status: PipelineRunStatus, startedAt: Instant, error: String? = null) =
        QuarkusTransaction.requiringNew().run {
            val pipeline = em.createQuery("SELECT p FROM Pipeline p WHERE p.tenant.id = :t", Pipeline::class.java)
                .setParameter("t", tenantId).resultList.first()
            PipelineRun().apply {
                this.pipeline = pipeline
                this.status = status
                this.startedAt = startedAt
                this.errorMessage = error
            }.also { it.persist() }
        }

    private fun deadLetter(channel: String) = QuarkusTransaction.requiringNew().call {
        ConsumerDeadLetter().apply {
            this.channel = channel
            payload = """{"kwh":91}"""
            error = "no handler"
        }.also {
            em.persist(it)
            deadLetterIds += it.id!!
        }
    }

    private fun findings(now: Instant = Instant.now()) = QuarkusTransaction.requiringNew().call { monitor.findings(now) }

    private fun failedAlert(tenantId: UUID, title: String) = QuarkusTransaction.requiringNew().run {
        val tenant = em.find(Tenant::class.java, tenantId)
        AlertHistory().apply {
            this.tenant = tenant
            this.channel = null
            this.title = title
            this.message = "could not send"
            severity = AlertSeverity.ERROR
            status = AlertStatus.FAILED
            lastError = "connection refused"
        }.also { it.persist() }
    }

    @Test
    fun `repeated run failures are reported once the threshold is reached`() {
        val tenantId = fixture("Load training")
        val now = Instant.now()
        monitor.failureThreshold = 3
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(600), "model fit failed")
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(500), "model fit failed")
        assertTrue(
            findings(now).none { it.check == PlatformHealthMonitor.CHECK_RUN_FAILURES },
            "two failures are not 'repeated' yet",
        )

        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(400), "model fit failed")
        val finding = findings(now).single { it.check == PlatformHealthMonitor.CHECK_RUN_FAILURES }
        assertEquals("ERROR", finding.severity)
        assertEquals(tenantId, finding.tenantId)
        assertTrue(finding.title.contains("Load training"), finding.title)
        assertTrue(finding.message.contains("model fit failed"), "the message should quote the failure")
    }

    @Test
    fun `failures outside the window are not counted`() {
        val tenantId = fixture()
        val now = Instant.now()
        monitor.failureThreshold = 2
        monitor.failureWindowSeconds = 60
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(3600))
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(3600))
        assertTrue(findings(now).none { it.check == PlatformHealthMonitor.CHECK_RUN_FAILURES })
    }

    @Test
    fun `an inactive pipeline is not reported`() {
        val tenantId = fixture()
        val now = Instant.now()
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(10))
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(20))
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(30))
        QuarkusTransaction.requiringNew().run {
            em.createQuery("UPDATE Pipeline p SET p.isActive = false WHERE p.tenant.id = :t")
                .setParameter("t", tenantId).executeUpdate()
        }
        assertTrue(findings(now).none { it.check == PlatformHealthMonitor.CHECK_RUN_FAILURES })
    }

    @Test
    fun `processing lag is reported once the oldest run is too old`() {
        val tenantId = fixture("Ingest")
        val now = Instant.now()
        monitor.lagThresholdSeconds = 300
        run(tenantId, PipelineRunStatus.RUNNING, now.minusSeconds(30))
        assertTrue(findings(now).none { it.check == PlatformHealthMonitor.CHECK_LAG })

        // An hour old, so the assertion does not depend on how precisely the column stores the
        // timestamp: `Duration.seconds` truncates, and a run 900 s old can read back as 899.
        run(tenantId, PipelineRunStatus.PENDING, now.minusSeconds(3600))
        val finding = findings(now).single { it.check == PlatformHealthMonitor.CHECK_LAG }
        assertEquals("WARNING", finding.severity)
        assertEquals(2L, finding.payload["unfinishedRuns"])
        assertTrue((finding.payload["oldestSeconds"] as Long) >= 3500, finding.payload.toString())
    }

    @Test
    fun `finished runs are not lag`() {
        val tenantId = fixture()
        val now = Instant.now()
        run(tenantId, PipelineRunStatus.SUCCESS, now.minusSeconds(3600))
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(3600))
        run(tenantId, PipelineRunStatus.CANCELLED, now.minusSeconds(3600))
        assertTrue(findings(now).none { it.check == PlatformHealthMonitor.CHECK_LAG })
    }

    @Test
    fun `dead letters are reported as a platform-wide problem`() {
        deadLetter("substation")
        deadLetter("substation")
        deadLetter("weather")
        val finding = findings().single { it.check == PlatformHealthMonitor.CHECK_DEAD_LETTERS }
        assertNull(finding.tenantId, "a dead letter belongs to no tenant")
        assertEquals(3L, finding.payload["deadLetters"])
        assertTrue(finding.message.contains("substation"), finding.message)
    }

    @Test
    fun `alerts that could not be delivered are reported`() {
        val tenantId = fixture()
        failedAlert(tenantId, "Boiler too hot")
        failedAlert(tenantId, "Pressure too low")
        val finding = findings().single { it.check == PlatformHealthMonitor.CHECK_UNDELIVERED }
        assertNull(finding.tenantId)
        assertEquals(2L, finding.payload["undeliveredAlerts"])
        assertTrue(finding.message.contains("health-tenant-"), "the message should name the tenant: ${finding.message}")
    }

    @Test
    fun `an unreachable administrator is not itself reported as an undelivered alert`() {
        val tenantId = fixture()
        failedAlert(tenantId, "Boiler too hot")
        val now = Instant.now()
        val raised = QuarkusTransaction.requiringNew().call { monitor.raise(findings(now), now) }
        // No admin addresses are configured, so nothing is delivered; the attempt is only recorded.
        assertEquals(0, raised)
        // The attempt is recorded as FAILED. Counting it again on the next scan would keep
        // generating mail about a mailbox that is not there.
        val second = findings(now).single { it.check == PlatformHealthMonitor.CHECK_UNDELIVERED }
        assertEquals(1L, second.payload["undeliveredAlerts"])
    }

    @Test
    fun `raising a finding alerts the tenant default channel and the administrators once per window`() {
        monitor.adminEmails = Optional.of("admin@example.com")
        val tenantId = fixture("Ingest")
        val now = Instant.now()
        val finding = PlatformHealthMonitor.Finding(
            check = PlatformHealthMonitor.CHECK_LAG,
            key = tenantId.toString(),
            severity = "WARNING",
            title = "Pipeline 'Ingest' is 900s behind",
            message = "2 unfinished runs",
            tenantId = tenantId,
            tenantName = "with-a-channel",
            payload = mapOf("unfinishedRuns" to 2),
        )

        val raised = QuarkusTransaction.requiringNew().call { monitor.raise(listOf(finding), now) }
        assertEquals(2, raised, "one tenant alert and one administrator email")
        assertEquals(1, mailbox.getMailsSentTo("ops@example.com").size, "the tenant's default channel")
        assertEquals(1, mailbox.getMailsSentTo("admin@example.com").size, "the administrators")
        assertTrue(
            mailbox.getMailsSentTo("admin@example.com").single().subject.contains("900s behind"),
            mailbox.getMailsSentTo("admin@example.com").single().subject,
        )

        // The same problem in the same window must not notify anybody again.
        mailbox.clear()
        val again = QuarkusTransaction.requiringNew().call { monitor.raise(listOf(finding), now.plusSeconds(60)) }
        assertEquals(0, again)
        assertTrue(mailbox.getMailsSentTo("ops@example.com").isEmpty())
        assertTrue(mailbox.getMailsSentTo("admin@example.com").isEmpty())
    }

    @Test
    fun `a platform-wide finding reaches the administrators and not a tenant channel`() {
        monitor.adminEmails = Optional.of("admin@example.com")
        fixture("Ingest")
        deadLetter("substation")
        val now = Instant.now()
        val raised = QuarkusTransaction.requiringNew().call { monitor.raise(findings(now), now) }
        assertEquals(1, raised)
        assertTrue(mailbox.getMailsSentTo("ops@example.com").isEmpty(), "it is nobody's dead letter")
        assertEquals(1, mailbox.getMailsSentTo("admin@example.com").size)
    }

    @Test
    fun `nothing is raised when the platform is healthy`() {
        fixture("Ingest")
        val now = Instant.now()
        assertTrue(findings(now).isEmpty(), "a tenant with a healthy pipeline and no channel traffic")
        assertEquals(0, QuarkusTransaction.requiringNew().call { monitor.raise(findings(now), now) })
        assertTrue(mailbox.getMailsSentTo("admin@example.com").isEmpty())
    }

    @Test
    fun `a tenant without a channel still tells the administrators`() {
        monitor.adminEmails = Optional.of("admin@example.com")
        val tenantId = QuarkusTransaction.requiringNew().call {
            val tenant = Tenant().apply { name = "no-channel-${UUID.randomUUID()}" }.also { it.persist() }
            tenantIds += tenant.id!!
            Pipeline().apply { this.tenant = tenant; name = "Silent"; isActive = true }.also { it.persist() }
            tenant.id!!
        }
        val now = Instant.now()
        monitor.failureThreshold = 1
        run(tenantId, PipelineRunStatus.FAILED, now.minusSeconds(10), "boom")
        val findings = QuarkusTransaction.requiringNew().call { monitor.findings(now) }
        QuarkusTransaction.requiringNew().call { monitor.raise(findings, now) }
        assertEquals(1, mailbox.getMailsSentTo("admin@example.com").size)
        // The tenant alert is recorded as FAILED, because there is nowhere to send it. That is
        // exactly the condition the undelivered-alert check exists to report.
        val undelivered = findings(now).single { it.check == PlatformHealthMonitor.CHECK_UNDELIVERED }
        assertEquals(1L, undelivered.payload["undeliveredAlerts"])
    }
}
