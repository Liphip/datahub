package com.dataturntable.engine

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.tenant.Tenant
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyBean

@QuarkusTest
class PipelineRunQueueTest {

    @Inject
    lateinit var queue: PipelineRunQueue

    @Inject
    lateinit var consumer: PipelineConsumer

    @Inject
    @AnyBean
    lateinit var messaging: InMemoryConnector

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var reaper: PendingRunReaper

    @Inject
    lateinit var logWriter: PipelineLogWriter

    @Inject
    lateinit var alertDispatcher: com.dataturntable.alert.AlertDispatcher

    private val mapper = ObjectMapper()
    private lateinit var tenantId: UUID
    private lateinit var pipelineId: UUID

    @BeforeEach
    fun setUp() {
        QuarkusTransaction.requiringNew().run {
            val tenant = Tenant().apply { name = "queue-${UUID.randomUUID()}" }.also { it.persist() }
            tenantId = tenant.id!!
            pipelineId = Pipeline().apply {
                this.tenant = tenant
                name = "Queue test"
                isActive = true
                graphDefinition = """{"nodes":[],"edges":[]}"""
            }.also { it.persist() }.id!!
        }
        messaging.sink<String>("pipeline-commands").clear()
    }

    @AfterEach
    fun cleanUp() {
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM PipelineRunNodeLog l WHERE l.run.pipeline.id = :p").setParameter("p", pipelineId).executeUpdate()
            em.createQuery("DELETE FROM PipelineRun r WHERE r.pipeline.id = :p").setParameter("p", pipelineId).executeUpdate()
            em.createQuery("DELETE FROM Pipeline p WHERE p.id = :p").setParameter("p", pipelineId).executeUpdate()
            em.createQuery("DELETE FROM Tenant t WHERE t.id = :t").setParameter("t", tenantId).executeUpdate()
        }
    }


    private fun run(id: UUID): PipelineRun? = QuarkusTransaction.requiringNew().call { em.find(PipelineRun::class.java, id) }
    private fun records() = messaging.sink<String>("pipeline-commands").received().map { it.payload }
    private fun source(offset: Long) = PipelineLogWriter.RecordSource(0, offset)

    @Test
    fun machineIngestPublishesWithoutTouchingTheDatabase() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1), partitionKey = "sensor-7")

        assertNull(run(runId))                                   // created by the executor, not by ingest
        val message = messaging.sink<String>("pipeline-commands").received().single()
        val key = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).get().key
        assertEquals("$pipelineId:sensor-7", key)
        val body = mapper.readTree(message.payload)
        assertEquals(runId.toString(), body["runId"].asText())
        assertEquals(1, body["data"]["v"].asInt())
        assertEquals(runId.toString(), body["data"]["_runId"].asText())

        assertTrue(consumer.consume(message.payload, source(1)).success)
        val created = run(runId)!!
        assertEquals(PipelineRunStatus.SUCCESS, created.status)
        assertEquals(TriggerType.DATA, created.triggerType)
        assertNotNull(created.queuedAt)
    }

    @Test
    fun uiRunsAreVisibleBeforeProcessing() {
        val runId = queue.enqueue(pipelineId, TriggerType.MANUAL, emptyMap(), persistRun = true)
        assertEquals(PipelineRunStatus.PENDING, run(runId)!!.status)
    }

    @Test
    fun resendWithTheSameIdempotencyKeyRunsOnce() {
        val first = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1), idempotencyKey = "dev-1:1700000000")
        val resend = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1), idempotencyKey = "dev-1:1700000000")
        val other = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 2), idempotencyKey = "dev-1:1700000060")
        assertEquals(first, resend)
        assertNotEquals(first, other)

        val (a, b, c) = records()
        assertTrue(consumer.consume(a, source(1)).success)
        assertEquals("skipped", consumer.consume(b, source(2)).stage)    // second copy of the same run
        assertTrue(consumer.consume(c, source(3)).success)
        assertEquals(1L, QuarkusTransaction.requiringNew().call {
            em.createQuery("SELECT COUNT(r) FROM PipelineRun r WHERE r.id = :id", java.lang.Long::class.java).setParameter("id", first).singleResult
        }.toLong())
    }

    @Test
    fun secondCopyIsSkippedWhileTheFirstIsStillRunning() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1), idempotencyKey = "k")
        queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1), idempotencyKey = "k")
        val (a, b) = records()
        // Copy a is claimed by an executor that is still working on it.
        assertTrue(QuarkusTransaction.requiringNew().call { logWriter.claimRun(runId, pipelineId, source = source(10)) })
        assertEquals("skipped", consumer.consume(b, source(11)).stage)
        assertEquals(PipelineRunStatus.RUNNING, run(runId)!!.status)
        // The executor of copy a dies; a is redelivered and runs to completion.
        assertTrue(consumer.consume(a, source(10)).success)
        assertEquals(PipelineRunStatus.SUCCESS, run(runId)!!.status)
    }

    @Test
    fun redeliveredRecordOfAFinishedRunIsSkipped() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1))
        val record = records().single()

        assertTrue(consumer.consume(record, source(5)).success)
        val finished = run(runId)!!
        assertEquals(PipelineRunStatus.SUCCESS, finished.status)

        // Executor died before committing the offset: the same record arrives again.
        val again = consumer.consume(record, source(5))
        assertEquals("skipped", again.stage)
        assertEquals(finished.completedAt, run(runId)!!.completedAt)
    }

    @Test
    fun runLeftRunningByADeadExecutorIsExecutedAgain() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1))
        val record = records().single()
        assertTrue(QuarkusTransaction.requiringNew().call { logWriter.claimRun(runId, pipelineId, source = source(7)) })

        assertTrue(consumer.consume(record, source(7)).success)
        assertEquals(PipelineRunStatus.SUCCESS, run(runId)!!.status)
    }

    @Test
    fun reapedRunIsStillProcessedWhenItsRecordArrivesLate() {
        val runId = queue.enqueue(pipelineId, TriggerType.MANUAL, mapOf("v" to 1), persistRun = true)
        val record = records().single()

        assertTrue(QuarkusTransaction.requiringNew().call { reaper.reap(java.time.Instant.now().plus(java.time.Duration.ofHours(1))) } >= 1)
        assertEquals(PipelineRunStatus.FAILED, run(runId)!!.status)

        assertTrue(consumer.consume(record).success)                // backlog drained after the reaper ran
        assertEquals(PipelineRunStatus.SUCCESS, run(runId)!!.status)
        assertEquals(null, run(runId)!!.errorMessage)
    }

    @Test
    fun failedRunKeepsItsInput() {
        QuarkusTransaction.requiringNew().run {
            em.find(Pipeline::class.java, pipelineId).graphDefinition =
                """{"nodes":[{"id":"n1","type":"ALERT","alertRule":{"type":"nope"}}],"edges":[]}"""
        }
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 5))
        val record = messaging.sink<String>("pipeline-commands").received().single().payload

        assertFalse(consumer.consume(record).success)
        val failed = run(runId)!!
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertEquals(5, mapper.readTree(failed.inputPayload)["v"].asInt())
    }

    @Test
    fun passThroughImportFeedsBuiltInRuleWithoutAnyModule() {
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { ex ->
                received += ex.requestBody.readAllBytes().decodeToString()
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
            start()
        }
        try {
            QuarkusTransaction.requiringNew().run {
                val channel = com.dataturntable.alert.AlertChannel().apply {
                    tenant = em.find(Tenant::class.java, tenantId); name = "hook"
                    channelType = com.dataturntable.alert.AlertChannelType.WEBHOOK
                    config = """{"url":"http://127.0.0.1:${server.address.port}/hook"}"""
                }.also { em.persist(it) }
                em.find(Pipeline::class.java, pipelineId).graphDefinition = """{"nodes":[
                    {"id":"in","type":"IMPORT"},
                    {"id":"hot","type":"ALERT","alertChannel":"${channel.id}",
                     "alertRule":{"type":"threshold","field":"temp","operator":">","value":80,"seriesField":"device","cooldownSeconds":0}}],
                    "edges":[{"source":"in","target":"hot"}]}"""
            }
            val sink = messaging.sink<String>("pipeline-commands")
            queue.enqueue(pipelineId, TriggerType.DATA, mapOf("device" to "b1", "temp" to 75))
            queue.enqueue(pipelineId, TriggerType.DATA, mapOf("device" to "b1", "temp" to 91))
            // MQTT-style delivery: the payload is unwrapped for the rule.
            queue.enqueue(pipelineId, TriggerType.DATA, mapOf("_connectorId" to "c", "payload" to mapOf("device" to "b2", "temp" to 99)))
            sink.received().forEach { assertTrue(consumer.consume(it.payload).success) }

            // Alert delivery goes through the transactional outbox, so the webhooks land after
            // consume() returns. Pump the outbox directly instead of waiting for the 5 s scheduler:
            // under full-suite load the scheduler does not reliably tick within the deadline, and
            // a failed first attempt is backed off by 10 s, so sleeping reddened CI intermittently
            // with an empty `received` even though the test passes in isolation.
            val deadline = System.currentTimeMillis() + 15_000
            while (received.size < 2 && System.currentTimeMillis() < deadline) {
                alertDispatcher.deliverDue()
                Thread.sleep(50)
            }

            assertEquals(2, received.size)
            assertTrue(received.any { "\"b1\"" in it } && received.any { "\"b2\"" in it }, received.toString())
        } finally {
            server.stop(0)
            QuarkusTransaction.requiringNew().run {
                em.createQuery("DELETE FROM AlertHistory h WHERE h.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                em.createQuery("DELETE FROM AlertChannel c WHERE c.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                em.createQuery("DELETE FROM AlertRuleState").executeUpdate()
            }
        }
    }

    @Test
    fun onlyConnectionLevelFailuresAreTreatedAsTransient() {
        assertTrue(isTransientInfrastructureFailure(SQLTransientConnectionException("pool timeout")))
        assertTrue(isTransientInfrastructureFailure(RuntimeException("wrapped", SQLException("gone", "08006"))))
        assertTrue(isTransientInfrastructureFailure(SQLException("terminating connection", "57P01")))
        assertTrue(isTransientInfrastructureFailure(SQLException("read-only transaction", "25006")))
        assertFalse(isTransientInfrastructureFailure(SQLException("duplicate key", "23505")))
        assertFalse(isTransientInfrastructureFailure(IllegalStateException("module failed")))
    }
}
