package com.dataturntable.execution

import com.dataturntable.engine.DockerService
import com.dataturntable.engine.ModuleExecutionException
import com.dataturntable.module.ModuleVersion
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

/** Development/trusted-install backend; all Docker details remain behind ExecutionBackend. */
@ApplicationScoped
class DockerExecutionBackend : ExecutionBackend {
    override val name = "docker"
    @PersistenceContext lateinit var em: EntityManager
    @jakarta.inject.Inject lateinit var docker: DockerService

    override fun submit(request: ExecutionRequest): ExecutionResult {
        val version = em.find(ModuleVersion::class.java, request.moduleVersionId)
            ?: return ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = "Module version not found")
        val type = version.module?.moduleType
            ?: return ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = "Module type not found")
        return try {
            val output = docker.executeModule(version, request.input, "execution", request.executionId.toString(), request.moduleType, request.connectorMetadata, request.limits)
            ExecutionResult(request.executionId, ExecutionStatus.SUCCESS, output = output.result, stdout = output.stdout, stderr = output.stderr, exitCode = output.exitCode)
        } catch (e: ModuleExecutionException) {
            ExecutionResult(request.executionId, if (e.message?.contains("timed out", true) == true) ExecutionStatus.TIMED_OUT else ExecutionStatus.FAILED, stdout = e.stdout, stderr = e.stderr, errorMessage = e.message, exitCode = e.exitCode)
        }
    }

    override fun cancel(executionId: java.util.UUID): Boolean {
        // Ephemeral executions are named `dt-execution-<id>-...`; removing the
        // matching container stops its process and is safe to retry.
        val containerIds = runCatching {
            ProcessBuilder("docker", "ps", "-aq", "--filter", "name=dt-execution-$executionId")
                .start()
                .let { process ->
                    process.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
                        .also { process.waitFor() }
                }
        }.getOrElse { return false }
        if (containerIds.isEmpty()) return false
        return containerIds.all { containerId ->
            runCatching {
                val process = ProcessBuilder("docker", "rm", "-f", containerId).start()
                process.waitFor() == 0
            }.getOrDefault(false)
        }
    }
}
