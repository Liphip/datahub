package com.dataturntable.api

import com.dataturntable.engine.WarmContainerPool
import com.dataturntable.monitoring.MonitoringSnapshotService
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunNodeLog
import com.dataturntable.pipeline.PipelineRunStatus
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Public monitoring endpoints for Grafana dashboards and external observability.
 * No authentication required — these expose aggregate statistics only, no tenant data.
 */
@Path("/api/v1/monitoring")
@Produces(MediaType.APPLICATION_JSON)
class MonitoringResource {

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var warmPool: WarmContainerPool

    @Inject
    lateinit var monitoringSnapshotService: MonitoringSnapshotService

    private val objectMapper = ObjectMapper()

    /**
     * GET /monitoring/pipeline-stats
     *
     * Returns aggregate pipeline run statistics over a configurable window.
     * Designed for Grafana JSON datasource or direct dashboard embedding.
     */
    @GET
    @Path("/pipeline-stats")
    fun pipelineStats(
        @QueryParam("sinceHours") sinceHours: Int?,
        @QueryParam("pipelineId") pipelineId: String?,
    ): Response {
        val since = Instant.now().minus((sinceHours ?: 24).toLong(), ChronoUnit.HOURS)

        val conditions = mutableListOf("r.startedAt >= :since")
        if (!pipelineId.isNullOrBlank()) conditions.add("r.pipeline.id = :pipelineId")

        val whereClause = conditions.joinToString(" AND ")

        // Total counts by status
        @Suppress("UNCHECKED_CAST")
        val statusCounts = em.createQuery(
            "SELECT r.status, COUNT(r) FROM PipelineRun r WHERE $whereClause GROUP BY r.status"
        ).apply {
            setParameter("since", since)
            if (!pipelineId.isNullOrBlank()) setParameter("pipelineId", UUID.fromString(pipelineId))
        }.resultList as List<Array<Any>>

        val statusMap = statusCounts.associate {
            (it[0] as PipelineRunStatus).name to (it[1] as Long)
        }

        // Average duration for completed runs (computed in code — no DB-specific date arithmetic)
        @Suppress("UNCHECKED_CAST")
        val completedRuns = em.createQuery(
            "SELECT r.startedAt, r.completedAt FROM PipelineRun r WHERE $whereClause AND r.completedAt IS NOT NULL"
        ).apply {
            setParameter("since", since)
            if (!pipelineId.isNullOrBlank()) setParameter("pipelineId", UUID.fromString(pipelineId))
        }.resultList as List<Array<Any?>>
        val avgDuration = if (completedRuns.isNotEmpty()) {
            completedRuns.mapNotNull { row ->
                val start = row[0] as? Instant ?: return@mapNotNull null
                val end = row[1] as? Instant ?: return@mapNotNull null
                java.time.Duration.between(start, end).toMillis().toDouble()
            }.average()
        } else null

        // Currently running
        val runningCount = em.createQuery(
            "SELECT COUNT(r) FROM PipelineRun r WHERE r.status = :running"
        ).setParameter("running", PipelineRunStatus.RUNNING)
            .singleResult as Long

        // Recent failures (last 10)
        @Suppress("UNCHECKED_CAST")
        val recentFailures = em.createQuery(
            "SELECT r FROM PipelineRun r WHERE r.status = :failed AND r.startedAt >= :since ORDER BY r.startedAt DESC"
        ).setParameter("failed", PipelineRunStatus.FAILED)
            .setParameter("since", since)
            .setMaxResults(10)
            .resultList as List<PipelineRun>

        val total = statusMap.values.sum()
        val successCount = statusMap.getOrDefault("SUCCESS", 0L)
        val failedCount = statusMap.getOrDefault("FAILED", 0L)
        val successRate = if (total > 0) (successCount.toDouble() / total * 100) else 0.0

        return Response.ok(mapOf(
            "sinceHours" to (sinceHours ?: 24),
            "total" to total,
            "statusCounts" to statusMap,
            "successRate" to "%.1f".format(successRate),
            "avgDurationMs" to avgDuration?.toLong(),
            "currentlyRunning" to runningCount,
            "recentFailures" to recentFailures.map { run ->
                mapOf(
                    "id" to run.id,
                    "pipelineId" to run.pipeline?.id,
                    "pipelineName" to run.pipeline?.name,
                    "status" to run.status.name,
                    "errorMessage" to run.errorMessage,
                    "startedAt" to run.startedAt,
                    "completedAt" to run.completedAt,
                )
            }
        )).build()
    }

    /**
     * GET /monitoring/module-stats
     *
     * Returns per-module-stage execution statistics from node logs.
     */
    @GET
    @Path("/module-stats")
    fun moduleStats(
        @QueryParam("sinceHours") sinceHours: Int?,
    ): Response {
        val since = Instant.now().minus((sinceHours ?: 24).toLong(), ChronoUnit.HOURS)

        @Suppress("UNCHECKED_CAST")
        val stats = em.createQuery(
            """SELECT l.nodeType, l.success, COUNT(l), AVG(l.durationMs), MAX(l.durationMs)
               FROM PipelineRunNodeLog l
               WHERE l.executedAt >= :since
               GROUP BY l.nodeType, l.success
               ORDER BY l.nodeType"""
        ).setParameter("since", since)
            .resultList as List<Array<Any?>>

        val moduleStats = stats.map { row ->
            mapOf(
                "stage" to row[0],
                "success" to row[1],
                "count" to row[2],
                "avgDurationMs" to (row[3] as? Double)?.toLong(),
                "maxDurationMs" to row[4],
            )
        }

        return Response.ok(mapOf(
            "sinceHours" to (sinceHours ?: 24),
            "stats" to moduleStats,
        )).build()
    }

    /**
     * GET /monitoring/container-pool
     *
     * Returns current warm container pool state for Grafana dashboards.
     */
    @GET
    @Path("/container-pool")
    fun containerPool(): Response {
        return Response.ok(mapOf(
            "poolEnabled" to warmPool.poolEnabled,
            "basePoolTarget" to warmPool.basePoolSize,
            "moduleCacheMax" to warmPool.moduleCacheMax,
        )).build()
    }

    @GET
    @Path("/summary")
    fun summary(): Response =
        Response.ok(monitoringSnapshotService.collect()).build()

    /**
     * GET /monitoring/recent-logs
     *
     * Returns recent module execution logs (last N node executions).
     * For Grafana table/log panels.
     */
    @GET
    @Path("/recent-logs")
    fun recentLogs(
        @QueryParam("limit") limit: Int?,
        @QueryParam("status") status: String?,
    ): Response {
        val maxRows = (limit ?: 50).coerceIn(1, 200)
        val successFilter = when (status?.lowercase()) {
            "success" -> true
            "failed", "failure" -> false
            else -> null
        }

        val jpql = StringBuilder("SELECT l FROM PipelineRunNodeLog l")
        if (successFilter != null) jpql.append(" WHERE l.success = :success")
        jpql.append(" ORDER BY l.executedAt DESC")

        val query = em.createQuery(jpql.toString(), PipelineRunNodeLog::class.java)
            .setMaxResults(maxRows)
        if (successFilter != null) query.setParameter("success", successFilter)

        val logs = query.resultList.map { log ->
            mapOf(
                "id" to log.id,
                "runId" to log.run?.id,
                "nodeId" to log.nodeId,
                "stage" to log.nodeType,
                "moduleName" to log.moduleName,
                "success" to log.success,
                "stdout" to log.stdout?.take(500),
                "stderr" to log.stderr?.take(500),
                "error" to log.errorMessage,
                "durationMs" to log.durationMs,
                "executedAt" to log.executedAt,
            )
        }

        return Response.ok(mapOf("logs" to logs)).build()
    }

    /**
     * GET /monitoring/pipeline-timeseries
     *
     * Returns time-bucketed run counts for time series graphs.
     */
    @GET
    @Path("/pipeline-timeseries")
    fun pipelineTimeseries(
        @QueryParam("sinceHours") sinceHours: Int?,
        @QueryParam("bucketMinutes") bucketMinutes: Int?,
    ): Response {
        val hours = (sinceHours ?: 24).coerceIn(1, 168)
        val bucket = (bucketMinutes ?: 60).coerceIn(5, 1440)
        val since = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS)

        @Suppress("UNCHECKED_CAST")
        val runs = em.createQuery(
            "SELECT r FROM PipelineRun r WHERE r.startedAt >= :since ORDER BY r.startedAt ASC",
            PipelineRun::class.java
        ).setParameter("since", since)
            .resultList

        // Bucket runs by time window
        val buckets = mutableMapOf<Long, MutableMap<String, Int>>()
        val bucketMs = bucket * 60_000L
        val baseMs = since.toEpochMilli()

        for (run in runs) {
            val offset = run.startedAt.toEpochMilli() - baseMs
            val bucketKey = baseMs + (offset / bucketMs) * bucketMs
            val statusMap = buckets.getOrPut(bucketKey) {
                mutableMapOf("SUCCESS" to 0, "FAILED" to 0, "RUNNING" to 0, "PENDING" to 0, "CANCELLED" to 0)
            }
            statusMap[run.status.name] = (statusMap[run.status.name] ?: 0) + 1
        }

        val timeseries = buckets.entries.sortedBy { it.key }.map { (ts, counts) ->
            mapOf(
                "timestamp" to Instant.ofEpochMilli(ts).toString(),
                "total" to counts.values.sum(),
            ) + counts
        }

        return Response.ok(mapOf(
            "sinceHours" to hours,
            "bucketMinutes" to bucket,
            "series" to timeseries,
        )).build()
    }
}
