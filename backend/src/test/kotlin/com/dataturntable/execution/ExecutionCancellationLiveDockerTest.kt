package com.dataturntable.execution

import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.eclipse.microprofile.context.ManagedExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cancellation was previously verified only at two disjoint layers: the state-machine transition
 * table (RUNNING -> CANCELLING -> CANCELLED is legal) and ExecutionServiceTest's fake in-memory
 * backend (proves cancelWithBackend *calls* backend.cancel(), nothing about whether that call
 * actually stops real work). DockerExecutionBackend.cancel() itself — which shells out to
 * `docker ps`/`docker rm -f` against the real container — had zero live coverage, and neither did
 * the claim that a cancelled execution's capacity slot becomes reusable. This exercises both
 * against a real Docker daemon (see DockerServiceHostileModuleTest.kt for the established pattern
 * of running real hostile/long-lived modules in this environment).
 */
class TightGlobalExecutionQuotaProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "test"
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "dataturntable.execution.max-concurrent-global" to "1",
        "dataturntable.execution.max-concurrent-per-tenant" to "1"
    )
}

@QuarkusTest
@TestProfile(TightGlobalExecutionQuotaProfile::class)
@org.junit.jupiter.api.condition.EnabledIf("com.dataturntable.test.DockerAvailability#available")
class ExecutionCancellationLiveDockerTest {

    @Inject
    lateinit var executionService: ExecutionService

    @PersistenceContext
    lateinit var em: EntityManager

    @Inject
    lateinit var backgroundExecutor: ManagedExecutor

    private fun createTenantAndModuleVersion(): Pair<UUID, UUID> = QuarkusTransaction.requiringNew().call<Pair<UUID, UUID>> {
        val tenant = Tenant().apply {
            this.name = "Execution Cancellation Tenant ${UUID.randomUUID()}"
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        em.persist(tenant)

        val module = CodeModule().apply {
            this.tenant = tenant
            this.name = "Cancellation Test Module"
            this.moduleType = ModuleType.PROCESSING
            this.isActive = true
            this.createdAt = Instant.now()
        }
        em.persist(module)

        val version = ModuleVersion().apply {
            this.module = module
            this.version = 1
            this.code = """
                |def process(input_data, query):
                |    import time
                |    time.sleep(60)
                |    return {'finished': True}
            """.trimMargin()
            this.requirements = ""
            this.contentFingerprint = "cancellation-test"
            this.isActive = true
            this.createdAt = Instant.now()
        }
        em.persist(version)
        em.flush()

        (tenant.id!! to version.id!!)
    }

    /** Polls `docker ps` for a container whose name contains the given fragment. */
    private fun containerRunning(nameFragment: String, timeoutSeconds: Long = 20): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            val ids = ProcessBuilder("docker", "ps", "-q", "--filter", "name=$nameFragment")
                .start()
                .let { process ->
                    val out = process.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
                    process.waitFor()
                    out
                }
            if (ids.isNotEmpty()) return true
            Thread.sleep(200)
        }
        return false
    }

    @Test
    fun `cancelling a real running Docker execution kills the container and the capacity slot becomes reusable`() {
        val (tenantId, moduleVersionId) = createTenantAndModuleVersion()
        val executionId = UUID.randomUUID()
        val environment = ExecutionEnvironment("python:3.11-slim", backend = "docker")
        val request = ExecutionRequest(
            executionId = executionId,
            tenantId = tenantId,
            moduleVersionId = moduleVersionId,
            input = mapOf("value" to 1),
            moduleType = ModuleType.PROCESSING,
            environment = environment,
            limits = ExecutionLimits(timeoutSeconds = 90)
        )

        // execute() blocks synchronously on the real Docker container (a 60s sleep), so it must
        // run on a background thread while this test thread cancels it mid-flight.
        val future = backgroundExecutor.submit<ExecutionResult> { executionService.execute(request) }

        val started = containerRunning("dt-execution-$executionId")
        if (!started && future.isDone) {
            // Surface why execute() finished (or failed) early instead of a bare timeout.
            org.junit.jupiter.api.Assertions.fail<Unit>("execute() completed before the container was observed running: ${future.get()}")
        }
        assertTrue(started, "expected the sleeping module's container to actually start running")

        val execution = em.find(Execution::class.java, executionId)
        val cancelStartedAt = System.currentTimeMillis()
        assertTrue(executionService.cancel(execution), "cancel() should succeed for a RUNNING execution")

        // The background execute() call must return promptly once its container is killed —
        // nowhere near the module's true 60s sleep duration or the 90s configured timeout.
        val result = future.get(30, TimeUnit.SECONDS)
        val cancelledWithinSeconds = (System.currentTimeMillis() - cancelStartedAt) / 1000
        assertTrue(cancelledWithinSeconds < 30, "expected prompt termination after cancel, took ${cancelledWithinSeconds}s")
        assertNotEquals(ExecutionStatus.SUCCESS, result.status, "a killed execution must not report SUCCESS")

        assertFalse(
            containerRunning("dt-execution-$executionId", timeoutSeconds = 5),
            "container must actually be removed after cancellation, not merely marked cancelled in the database"
        )

        // Global limit is 1 (TightGlobalExecutionQuotaProfile). If the cancelled execution's slot
        // were leaked, this second submission would be rejected with a quota-exceeded exception.
        val secondExecution = QuarkusTransaction.requiringNew().call<Execution> {
            executionService.submit(tenantId, moduleVersionId, environment, null)
        }
        assertEquals(ExecutionStatus.QUEUED, secondExecution.status)
    }
}
