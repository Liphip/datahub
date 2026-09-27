package com.dataturntable.engine

import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.PipelineVersion
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A pipeline run must execute the exact graph its PipelineVersion was pinned to at trigger time,
 * not whatever the pipeline's live graphDefinition has become by the time the run actually
 * executes. Without this, editing a pipeline between "run triggered" and "run consumed from
 * Kafka" would silently run the new graph instead of the pinned one.
 */
@QuarkusTest
class PipelineConsumerVersionPinningTest {

    @Inject
    lateinit var pipelineConsumer: PipelineConsumer

    @PersistenceContext
    lateinit var em: EntityManager

    @Test
    fun `execution uses the run's pinned PipelineVersion graph, not the pipeline's current live graph`() {
        val (pipelineId, runId) = QuarkusTransaction.requiringNew().call<Pair<java.util.UUID, java.util.UUID>> {
            val tenant = Tenant().apply {
                this.name = "Version Pinning Tenant ${java.util.UUID.randomUUID()}"
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            }
            em.persist(tenant)

            val pipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Version Pinning Pipeline"
                this.graphDefinition = """{"nodes":[],"edges":[],"marker":"live-v2"}"""
                this.isActive = true
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            }
            em.persist(pipeline)
            em.flush()

            // Version 1 is what the run below will be pinned to.
            val pinnedVersion = PipelineVersion().apply {
                this.pipeline = pipeline
                this.version = 1
                this.graphDefinition = """{"nodes":[],"edges":[],"marker":"pinned-v1"}"""
                this.schemaVersion = 1
                this.contentFingerprint = "v1"
                this.createdAt = Instant.now()
            }
            em.persist(pinnedVersion)
            em.flush()

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.pipelineVersion = pinnedVersion
                this.status = PipelineRunStatus.PENDING
                this.triggerType = TriggerType.MANUAL
            }
            em.persist(run)
            em.flush()

            pipeline.id!! to run.id!!
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
        val resolvedGraph = pipelineConsumer.resolveExecutionGraphJson(pipeline, runId)

        assertEquals("""{"nodes":[],"edges":[],"marker":"pinned-v1"}""", resolvedGraph)
    }

    @Test
    fun `execution falls back to the live pipeline graph for legacy runs with no pinned version`() {
        val (pipelineId, runId) = QuarkusTransaction.requiringNew().call<Pair<java.util.UUID, java.util.UUID>> {
            val tenant = Tenant().apply {
                this.name = "Legacy Run Tenant ${java.util.UUID.randomUUID()}"
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            }
            em.persist(tenant)

            val pipeline = Pipeline().apply {
                this.tenant = tenant
                this.name = "Legacy Run Pipeline"
                this.graphDefinition = """{"nodes":[],"edges":[],"marker":"live-only"}"""
                this.isActive = true
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
            }
            em.persist(pipeline)
            em.flush()

            val run = PipelineRun().apply {
                this.pipeline = pipeline
                this.pipelineVersion = null
                this.status = PipelineRunStatus.PENDING
                this.triggerType = TriggerType.MANUAL
            }
            em.persist(run)
            em.flush()

            pipeline.id!! to run.id!!
        }

        val pipeline = em.find(Pipeline::class.java, pipelineId)
        val resolvedGraph = pipelineConsumer.resolveExecutionGraphJson(pipeline, runId)

        assertEquals("""{"nodes":[],"edges":[],"marker":"live-only"}""", resolvedGraph)
    }
}
