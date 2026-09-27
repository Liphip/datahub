package com.dataturntable.alert

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.tenant.Tenant
import com.sun.net.httpserver.HttpServer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import java.util.UUID

@QuarkusTest
class AlertRuleEvaluatorTest {

    @Inject
    lateinit var evaluator: AlertRuleEvaluator

    @Inject
    lateinit var scheduler: AlertRuleScheduler

    @Inject
    lateinit var em: EntityManager

    private val t0: Instant = Instant.parse("2026-09-25T10:00:00Z")
    private val createdTenants = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        QuarkusTransaction.requiringNew().run {
            em.createQuery("DELETE FROM AlertRuleState").executeUpdate()
            createdTenants.forEach { tenantId ->
                listOf("AlertHistory", "AlertChannel", "Pipeline").forEach { entity ->
                    em.createQuery("DELETE FROM $entity e WHERE e.tenant.id = :t").setParameter("t", tenantId).executeUpdate()
                }
                em.createQuery("DELETE FROM Tenant t WHERE t.id = :t").setParameter("t", tenantId).executeUpdate()
            }
        }
        createdTenants.clear()
    }

    private fun rule(vararg entries: Pair<String, Any?>) = AlertRuleConfig.parse(mapOf(*entries))

    private fun eval(rule: AlertRuleConfig, record: Any, at: Instant, pipelineId: UUID = PIPELINE, node: String = "n1") =
        evaluator.evaluate(pipelineId, node, rule, record, at)

    @Test
    fun parseRejectsIncompleteRules() {
        assertThrows<IllegalArgumentException> { rule("type" to "nope") }
        assertThrows<IllegalArgumentException> { rule("type" to "threshold", "value" to 5) }
        assertThrows<IllegalArgumentException> { rule("type" to "threshold", "field" to "v") }
        assertThrows<IllegalArgumentException> { rule("type" to "rate_of_change", "field" to "v") }
        assertThrows<IllegalArgumentException> { rule("type" to "time_window", "field" to "v", "value" to 1) }
        assertThrows<IllegalArgumentException> { rule("type" to "absence") }
        assertThrows<IllegalArgumentException> { rule("type" to "threshold", "field" to "v", "operator" to ">", "value" to "high") }
        assertThrows<IllegalArgumentException> { rule("type" to "absence", "timeoutSeconds" to 0) }
    }

    @Test
    fun thresholdFiresOnlyWhenConditionHolds() {
        val r = rule("type" to "threshold", "field" to "sensor.temp", "operator" to ">", "value" to 80, "severity" to "critical")
        assertTrue(eval(r, mapOf("sensor" to mapOf("temp" to 79.9)), t0).isEmpty())
        val alert = eval(r, mapOf("sensor" to mapOf("temp" to 85)), t0).single()
        assertEquals("critical", alert.severity)
        assertEquals("sensor.temp > 80", alert.title)

        val status = rule("type" to "threshold", "field" to "state", "operator" to "==", "value" to "FAULT")
        assertEquals(1, eval(status, mapOf("state" to "FAULT"), t0).size)
        assertTrue(eval(status, mapOf("state" to "OK"), t0).isEmpty())
    }

    @Test
    fun thresholdEvaluatesEveryRecordOfABatch() {
        val r = rule("type" to "threshold", "field" to "v", "operator" to ">=", "value" to 10, "seriesField" to "id")
        val alerts = eval(r, listOf(mapOf("id" to "a", "v" to 12), mapOf("id" to "b", "v" to 3), mapOf("id" to "c", "v" to 10)), t0)
        assertEquals(listOf("a", "c"), alerts.map { it.seriesKey })
    }

    @Test
    fun rateOfChangeComparesWithPreviousValuePerSeries() {
        val r = rule("type" to "rate_of_change", "field" to "v", "delta" to 5, "intervalSeconds" to 60,
            "seriesField" to "id", "timestampField" to "ts")
        assertTrue(eval(r, mapOf("id" to "a", "v" to 10, "ts" to t0.epochSecond), t0).isEmpty())          // first value
        assertTrue(eval(r, mapOf("id" to "a", "v" to 14, "ts" to t0.epochSecond + 60), t0).isEmpty())     // +4 / min
        assertTrue(eval(r, mapOf("id" to "b", "v" to 100, "ts" to t0.epochSecond + 60), t0).isEmpty())    // other series
        val alert = eval(r, mapOf("id" to "a", "v" to 24, "ts" to t0.epochSecond + 90), t0).single()      // +10 in 30 s = +20 / min
        assertEquals("a", alert.seriesKey)
        assertEquals(20.0, alert.payload["changePerInterval"])
        // A late/duplicate record does not move the state backwards or alert again.
        assertTrue(eval(r, mapOf("id" to "a", "v" to 0, "ts" to t0.epochSecond + 30), t0).isEmpty())
    }

    @Test
    fun rateOfChangeHonoursDirection() {
        val up = rule("type" to "rate_of_change", "field" to "v", "delta" to 1, "direction" to "up", "timestampField" to "ts")
        eval(up, mapOf("v" to 10, "ts" to t0.epochSecond), t0, node = "up")
        assertTrue(eval(up, mapOf("v" to 0, "ts" to t0.epochSecond + 60), t0, node = "up").isEmpty())
        assertEquals(1, eval(up, mapOf("v" to 5, "ts" to t0.epochSecond + 120), t0, node = "up").size)
    }

    @Test
    fun timeWindowAggregatesSamplesInsideTheWindow() {
        val r = rule("type" to "time_window", "field" to "v", "aggregation" to "avg", "windowSeconds" to 60,
            "operator" to ">", "value" to 50, "timestampField" to "ts")
        assertTrue(eval(r, mapOf("v" to 40, "ts" to t0.epochSecond), t0).isEmpty())
        assertTrue(eval(r, mapOf("v" to 50, "ts" to t0.epochSecond + 10), t0).isEmpty())          // avg 45
        val alert = eval(r, mapOf("v" to 90, "ts" to t0.epochSecond + 20), t0).single()           // avg 60
        assertEquals(3, alert.payload["samples"])
        // At +90 s all earlier samples are older than 60 s: the window only holds 10.
        assertTrue(eval(r, mapOf("v" to 10, "ts" to t0.epochSecond + 90), t0).isEmpty())
        val count = rule("type" to "time_window", "field" to "v", "aggregation" to "count", "windowSeconds" to 60,
            "operator" to ">=", "value" to 2, "timestampField" to "ts")
        eval(count, mapOf("v" to 1, "ts" to t0.epochSecond), t0, node = "count")
        assertEquals(1, eval(count, mapOf("v" to 1, "ts" to t0.epochSecond + 5), t0, node = "count").size)
        // Equal measurements are real data and count twice.
        assertEquals(1, eval(count, mapOf("v" to 1, "ts" to t0.epochSecond + 5), t0, node = "count").size)
        assertEquals(4.0, (eval(count, mapOf("v" to 9), t0, node = "count").single().payload["aggregate"]))
    }

    @Test
    fun replayedRunChangesStateAndRaisesAlertsOnlyOnce() {
        val tenantAndPipeline = QuarkusTransaction.requiringNew().call {
            val tenant = Tenant().apply { name = "replay-${UUID.randomUUID()}" }.also { it.persist() }
            createdTenants += tenant.id!!
            val channel = AlertChannel().apply {
                this.tenant = tenant; name = "ops"; channelType = AlertChannelType.WEBHOOK; config = """{"url":"http://127.0.0.1:9/none"}"""
            }.also { it.persist() }
            val pipeline = Pipeline().apply { this.tenant = tenant; name = "Replay" }.also { it.persist() }
            pipeline.id!! to channel.id!!.toString()
        }
        val (pipelineId, channel) = tenantAndPipeline
        val window = rule("type" to "time_window", "field" to "v", "aggregation" to "count", "windowSeconds" to 600,
            "operator" to ">=", "value" to 1, "cooldownSeconds" to 0)
        val run1 = UUID.randomUUID()
        val batch = listOf(mapOf("v" to 1), mapOf("v" to 1))
        fun evalRun(runId: UUID) = evaluator.evaluate(pipelineId, "w", window, batch, t0, AlertTarget(channel, runId))

        val first = evalRun(run1)
        assertEquals(2, first.size)
        assertTrue(first.all { it.alertId != null })
        // The executor died after the evaluation committed; the run is executed again.
        assertTrue(evalRun(run1).isEmpty())
        val samples = QuarkusTransaction.requiringNew().call {
            em.find(AlertRuleState::class.java, AlertRuleStateId(pipelineId, "w", "")).windowSamples
        }
        assertEquals(2, samples!!.split("],[").size)                      // not four
        assertEquals(2L, QuarkusTransaction.requiringNew().call {
            em.createQuery("SELECT COUNT(a) FROM AlertHistory a WHERE a.pipeline.id = :p", java.lang.Long::class.java)
                .setParameter("p", pipelineId).singleResult
        }.toLong())
        // The next run is applied normally.
        assertEquals(1, evaluator.evaluate(pipelineId, "w", window, mapOf("v" to 1), t0, AlertTarget(channel, UUID.randomUUID())).size)
    }

    @Test
    fun thresholdAlertsOfAReplayedRunAreNotEnqueuedAgain() {
        val (pipelineId, channel) = QuarkusTransaction.requiringNew().call {
            val tenant = Tenant().apply { name = "replay-${UUID.randomUUID()}" }.also { it.persist() }
            createdTenants += tenant.id!!
            val channel = AlertChannel().apply {
                this.tenant = tenant; name = "ops"; channelType = AlertChannelType.WEBHOOK; config = """{"url":"http://127.0.0.1:9/none"}"""
            }.also { it.persist() }
            Pipeline().apply { this.tenant = tenant; name = "Replay" }.also { it.persist() }.id!! to channel.id!!.toString()
        }
        val r = rule("type" to "threshold", "field" to "v", "operator" to ">", "value" to 1, "cooldownSeconds" to 0)
        val runId = UUID.randomUUID()
        val first = evaluator.evaluate(pipelineId, "t", r, mapOf("v" to 5), t0, AlertTarget(channel, runId)).single()
        val replay = evaluator.evaluate(pipelineId, "t", r, mapOf("v" to 5), t0, AlertTarget(channel, runId)).single()
        assertEquals(first.alertId, replay.alertId)
    }

    @Test
    fun absenceFiresOncePerSilenceAndResetsWhenDataReturns() {
        val r = rule("type" to "absence", "timeoutSeconds" to 60)
        // First check starts watching (grace period) instead of alerting immediately.
        assertTrue(evaluator.claimAbsences(PIPELINE, "abs", r, t0).isEmpty())
        assertTrue(evaluator.claimAbsences(PIPELINE, "abs", r, t0.plusSeconds(30)).isEmpty())
        assertEquals(1, evaluator.claimAbsences(PIPELINE, "abs", r, t0.plusSeconds(61)).size)
        assertTrue(evaluator.claimAbsences(PIPELINE, "abs", r, t0.plusSeconds(300)).isEmpty())    // same silence
        eval(r, mapOf("v" to 1), t0.plusSeconds(400), node = "abs")                                  // data returns
        assertTrue(evaluator.claimAbsences(PIPELINE, "abs", r, t0.plusSeconds(430)).isEmpty())
        assertEquals(1, evaluator.claimAbsences(PIPELINE, "abs", r, t0.plusSeconds(461)).size)    // new silence
    }

    @Test
    fun absenceTracksEachSeries() {
        val r = rule("type" to "absence", "timeoutSeconds" to 60, "seriesField" to "id")
        eval(r, mapOf("id" to "a"), t0, node = "series")
        eval(r, mapOf("id" to "b"), t0.plusSeconds(50), node = "series")
        val alerts = evaluator.claimAbsences(PIPELINE, "series", r, t0.plusSeconds(70))
        assertEquals(listOf("a"), alerts.map { it.seriesKey })
    }

    @Test
    fun schedulerDispatchesAbsenceAlertsOfActivePipelines() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { ex ->
                received += ex.requestBody.readAllBytes().decodeToString()
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
            start()
        }
        try {
            QuarkusTransaction.requiringNew().run {
                val tenant = Tenant().apply { name = "rules-${UUID.randomUUID()}" }.also { it.persist() }
                createdTenants += tenant.id!!
                val channel = AlertChannel().apply {
                    this.tenant = tenant; name = "ops"; channelType = AlertChannelType.WEBHOOK
                    config = """{"url":"http://127.0.0.1:${server.address.port}/hook"}"""
                }.also { it.persist() }
                Pipeline().apply {
                    this.tenant = tenant
                    name = "Boiler"
                    isActive = true
                    graphDefinition = """{"nodes":[{"id":"quiet","type":"ALERT","alertChannel":"${channel.id}",
                        "alertRule":{"type":"absence","timeoutSeconds":60,"severity":"critical"}}],"edges":[]}"""
                }.persist()
            }

            assertEquals(0, scheduler.checkAbsences(t0))                  // starts watching
            assertEquals(1, scheduler.checkAbsences(t0.plusSeconds(61)))
            assertEquals(0, scheduler.checkAbsences(t0.plusSeconds(120)))
            assertEquals(1, received.size)
            assertTrue(received.single().contains("\"severity\":\"CRITICAL\""), received.single())
            assertTrue(received.single().contains("No data"), received.single())
        } finally {
            server.stop(0)
        }
    }

    companion object {
        private val PIPELINE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a1e7")
    }
}
