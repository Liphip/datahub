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
            em.createQuery("DELETE FROM PipelineRun r WHERE r.pipeline.id = :p").setParameter("p", pipelineId).executeUpdate()
            em.createQuery("DELETE FROM Pipeline p WHERE p.id = :p").setParameter("p", pipelineId).executeUpdate()
            em.createQuery("DELETE FROM Tenant t WHERE t.id = :t").setParameter("t", tenantId).executeUpdate()
        }
    }

    private fun run(id: UUID): PipelineRun = QuarkusTransaction.requiringNew().call { em.find(PipelineRun::class.java, id) }

    @Test
    fun enqueueCommitsTheRunAndPublishesKeyedMessage() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1), partitionKey = "sensor-7")

        assertEquals(PipelineRunStatus.PENDING, run(runId).status)   // visible to other nodes before the message
        val message = messaging.sink<String>("pipeline-commands").received().single()
        val key = message.getMetadata(OutgoingKafkaRecordMetadata::class.java).get().key
        assertEquals("$pipelineId:sensor-7", key)
        val body = mapper.readTree(message.payload)
        assertEquals(runId.toString(), body["runId"].asText())
        assertEquals(1, body["data"]["v"].asInt())
        assertEquals(runId.toString(), body["data"]["_runId"].asText())
    }

    @Test
    fun redeliveredRecordOfAFinishedRunIsSkipped() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1))
        val record = messaging.sink<String>("pipeline-commands").received().single().payload

        assertTrue(consumer.consume(record).success)
        val finished = run(runId)
        assertEquals(PipelineRunStatus.SUCCESS, finished.status)

        // Executor died before committing the offset: the same record arrives again.
        val again = consumer.consume(record)
        assertEquals("skipped", again.stage)
        assertEquals(finished.completedAt, run(runId).completedAt)
    }

    @Test
    fun runLeftRunningByADeadExecutorIsExecutedAgain() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1))
        val record = messaging.sink<String>("pipeline-commands").received().single().payload
        QuarkusTransaction.requiringNew().run { em.find(PipelineRun::class.java, runId).status = PipelineRunStatus.RUNNING }

        assertTrue(consumer.consume(record).success)
        assertEquals(PipelineRunStatus.SUCCESS, run(runId).status)
    }

    @Test
    fun reapedRunIsStillProcessedWhenItsRecordArrivesLate() {
        val runId = queue.enqueue(pipelineId, TriggerType.DATA, mapOf("v" to 1))
        val record = messaging.sink<String>("pipeline-commands").received().single().payload

        assertTrue(QuarkusTransaction.requiringNew().call { reaper.reap(java.time.Instant.now().plus(java.time.Duration.ofHours(1))) } >= 1)
        assertEquals(PipelineRunStatus.FAILED, run(runId).status)

        assertTrue(consumer.consume(record).success)                // backlog drained after the reaper ran
        assertEquals(PipelineRunStatus.SUCCESS, run(runId).status)
        assertEquals(null, run(runId).errorMessage)
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
        val failed = run(runId)
        assertEquals(PipelineRunStatus.FAILED, failed.status)
        assertEquals(5, mapper.readTree(failed.inputPayload)["v"].asInt())
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
