package com.dataturntable.alert

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.pipeline.Pipeline
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Evaluates `absence` rules, which fire because data does *not* arrive and therefore
 * cannot be triggered by pipeline input. Runs on every pipeline-executor replica;
 * [AlertRuleEvaluator.claimAbsences] guarantees each silence is reported once.
 */
@ApplicationScoped
class AlertRuleScheduler {

    private val log = Logger.getLogger(AlertRuleScheduler::class.java)
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Inject
    lateinit var evaluator: AlertRuleEvaluator

    @Inject
    lateinit var alertDispatcher: AlertDispatcher

    @PersistenceContext
    lateinit var em: EntityManager

    @Scheduled(
        every = "\${dataturntable.alerts.absence-check-interval:15s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun scheduledAbsenceCheck() {
        if (!runtimeRoleConfig.ownsPipelineRuntime()) return
        try {
            checkAbsences(Instant.now())
        } catch (e: Exception) {
            log.warnf("Absence check failed: %s", e.message)
        }
    }

    /** Checks all absence rules of active pipelines; returns the number of alerts raised. */
    @Transactional
    fun checkAbsences(now: Instant): Int {
        var raised = 0
        val pipelines = em.createQuery("SELECT p FROM Pipeline p WHERE p.isActive = true", Pipeline::class.java).resultList
        for (pipeline in pipelines) {
            val pipelineId = pipeline.id ?: continue
            for ((nodeId, node) in absenceNodes(pipeline)) {
                val rule = try {
                    AlertRuleConfig.parse(node["alertRule"] as Map<*, *>)
                } catch (e: IllegalArgumentException) {
                    log.warnf("Pipeline %s node %s: invalid alert rule: %s", pipelineId, nodeId, e.message)
                    continue
                }
                for (alert in evaluator.claimAbsences(pipelineId, nodeId, rule, now)) {
                    alertDispatcher.dispatchRuleAlert(pipeline, nodeId, node["alertChannel"]?.toString(), rule, alert)
                    raised++
                }
            }
        }
        return raised
    }

    private fun absenceNodes(pipeline: Pipeline): List<Pair<String, Map<*, *>>> {
        val graph = pipeline.graphDefinition?.let {
            runCatching { objectMapper.readValue(it, Map::class.java) }.getOrNull()
        } ?: return emptyList()
        return (graph["nodes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { node ->
            val rule = node["alertRule"] as? Map<*, *> ?: return@mapNotNull null
            if (node["type"]?.toString()?.uppercase() != "ALERT") return@mapNotNull null
            if (AlertRuleType.of(rule["type"]?.toString()) != AlertRuleType.ABSENCE) return@mapNotNull null
            val nodeId = node["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            nodeId to node
        }
    }
}
