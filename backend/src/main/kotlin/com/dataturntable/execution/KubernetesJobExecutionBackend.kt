package com.dataturntable.execution

import com.dataturntable.module.ModuleVersion
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files

sealed interface KubernetesJobObservation {
    data class State(val status: ExecutionStatus) : KubernetesJobObservation
    data object NotFound : KubernetesJobObservation
    data object Unavailable : KubernetesJobObservation
}

/**
 * Kubernetes-native execution backend. It creates a batch/v1 Job through the
 * Kubernetes API; it never mounts or invokes a Docker socket in a workload.
 */
@ApplicationScoped
class KubernetesJobExecutionBackend : ExecutionBackend {
    override val name = "kubernetes"
    @PersistenceContext lateinit var em: EntityManager
    @ConfigProperty(name = "dataturntable.kubernetes.api-server", defaultValue = "https://kubernetes.default.svc") lateinit var apiServer: String
    @ConfigProperty(name = "dataturntable.kubernetes.namespace", defaultValue = "default") lateinit var namespace: String
    @ConfigProperty(name = "dataturntable.kubernetes.token-path", defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token") lateinit var tokenPath: String
    private val mapper = com.dataturntable.json.JsonObjectMappers.create()

    override fun submit(request: ExecutionRequest): ExecutionResult {
        if (request.limits.allowNetwork) {
            return ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = "Kubernetes network access is not enabled for user Jobs")
        }
        val version = em.find(ModuleVersion::class.java, request.moduleVersionId)
            ?: return ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = "Module version not found")
        if (apiServer.isBlank() || !Files.isReadable(java.nio.file.Path.of(tokenPath))) {
            return ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = "Kubernetes workload identity is not configured")
        }
        return runCatching {
            val connection = URI.create("${apiServer.trimEnd('/')}/apis/batch/v1/namespaces/$namespace/jobs").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${Files.readString(java.nio.file.Path.of(tokenPath)).trim()}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(mapper.writeValueAsBytes(jobManifest(request, version.code ?: ""))) }
            if (connection.responseCode !in 200..299) throw IllegalStateException("Kubernetes Job creation failed with HTTP ${connection.responseCode}")
            ExecutionResult(request.executionId, ExecutionStatus.QUEUED)
        }.getOrElse { ExecutionResult(request.executionId, ExecutionStatus.FAILED, errorMessage = it.message) }
    }

    override fun cancel(executionId: java.util.UUID): Boolean {
        if (apiServer.isBlank() || !Files.isReadable(java.nio.file.Path.of(tokenPath))) return false
        return runCatching {
            val url = "${apiServer.trimEnd('/')}/apis/batch/v1/namespaces/$namespace/jobs/${jobName(executionId)}?propagationPolicy=Background"
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", "Bearer ${Files.readString(java.nio.file.Path.of(tokenPath)).trim()}")
            val status = connection.responseCode
            status in 200..299 || status == HttpURLConnection.HTTP_NOT_FOUND
        }.getOrDefault(false)
    }

    /** Reads the native Job state; callers reconcile it into the durable execution state. */
    fun observe(executionId: java.util.UUID): KubernetesJobObservation {
        if (apiServer.isBlank() || !Files.isReadable(java.nio.file.Path.of(tokenPath))) return KubernetesJobObservation.Unavailable
        return runCatching {
            val url = "${apiServer.trimEnd('/')}/apis/batch/v1/namespaces/$namespace/jobs/${jobName(executionId)}"
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer ${Files.readString(java.nio.file.Path.of(tokenPath)).trim()}")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> KubernetesJobObservation.NotFound
                in 200..299 -> {
                    @Suppress("UNCHECKED_CAST")
                    val job = mapper.readValue(connection.inputStream, Map::class.java) as Map<String, Any?>
                    KubernetesJobObservation.State(statusFromJob(job))
                }
                else -> throw IllegalStateException("Kubernetes Job status read failed with HTTP $code")
            }
        }.getOrElse { KubernetesJobObservation.Unavailable }
    }

    internal fun jobName(executionId: java.util.UUID) = "dt-exec-$executionId".lowercase()

    internal fun statusFromJob(job: Map<String, Any?>): ExecutionStatus {
        val status = job["status"] as? Map<*, *> ?: return ExecutionStatus.QUEUED
        val conditions = status["conditions"] as? List<*> ?: emptyList<Any>()
        val complete = conditions.filterIsInstance<Map<*, *>>().firstOrNull {
            it["type"]?.toString() == "Complete" && it["status"]?.toString().equals("True", ignoreCase = true)
        }
        if (complete != null) return ExecutionStatus.SUCCESS
        val failed = conditions.filterIsInstance<Map<*, *>>().firstOrNull {
            it["type"]?.toString() == "Failed" && it["status"]?.toString().equals("True", ignoreCase = true)
        }
        if (failed != null) {
            return if (failed["reason"]?.toString() == "DeadlineExceeded") ExecutionStatus.TIMED_OUT else ExecutionStatus.FAILED
        }
        val active = (status["active"] as? Number)?.toInt() ?: 0
        return if (active > 0 || status["startTime"] != null) ExecutionStatus.RUNNING else ExecutionStatus.QUEUED
    }

    internal fun jobManifest(request: ExecutionRequest, code: String): Map<String, Any> {
        val name = jobName(request.executionId)
        val limits = request.limits
        val container = mapOf(
            "name" to "module",
            "image" to request.environment.runtimeImage,
            "command" to listOf("python", "-c", code),
            "resources" to mapOf(
                "requests" to mapOf("cpu" to limits.cpuCores.toString(), "memory" to "${limits.memoryMiB}Mi"),
                "limits" to mapOf("cpu" to limits.cpuCores.toString(), "memory" to "${limits.memoryMiB}Mi", "ephemeral-storage" to "${limits.temporaryDiskMiB}Mi")
            ),
            "securityContext" to mapOf("allowPrivilegeEscalation" to false, "readOnlyRootFilesystem" to true, "capabilities" to mapOf("drop" to listOf("ALL"))),
            "env" to listOf(mapOf("name" to "INPUT_DATA", "value" to mapper.writeValueAsString(request.input)))
        )
        val podSpec = mapOf(
            "restartPolicy" to "Never", "automountServiceAccountToken" to false,
            "securityContext" to mapOf("runAsNonRoot" to true, "runAsUser" to 1000, "runAsGroup" to 1000, "seccompProfile" to mapOf("type" to "RuntimeDefault")),
            "containers" to listOf(container)
        )
        return mapOf(
            "apiVersion" to "batch/v1", "kind" to "Job",
            "metadata" to mapOf("name" to name, "labels" to mapOf("app" to "dataturntable-execution", "executionId" to request.executionId.toString(), "tenantId" to request.tenantId.toString())),
            "spec" to mapOf("ttlSecondsAfterFinished" to 300, "activeDeadlineSeconds" to limits.timeoutSeconds, "template" to mapOf("spec" to podSpec))
        )
    }
}
