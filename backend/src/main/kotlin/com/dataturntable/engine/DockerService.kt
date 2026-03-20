package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ModuleExecutionOutput(
    val result: Any?,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long
)

class ModuleExecutionException(
    message: String,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

private data class RawContainerResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long
)

@ApplicationScoped
class DockerService {
    
    private val LOG = Logger.getLogger(DockerService::class.java)
    private val objectMapper = ObjectMapper()
    
    @ConfigProperty(name = "dataturntable.docker.module.base-image", defaultValue = "python:3.11-slim")
    lateinit var baseImage: String
    
    @ConfigProperty(name = "dataturntable.docker.network", defaultValue = "dataturntable")
    lateinit var networkName: String

    @ConfigProperty(name = "dataturntable.docker.allow-network", defaultValue = "false")
    var allowNetwork: Boolean = false
    
    @ConfigProperty(name = "dataturntable.docker.resources.cpu-limit", defaultValue = "1.0")
    lateinit var cpuLimit: String
    
    @ConfigProperty(name = "dataturntable.docker.resources.memory-limit", defaultValue = "512m")
    lateinit var memoryLimit: String
    
    @ConfigProperty(name = "dataturntable.docker.timeout-seconds", defaultValue = "300")
    var timeoutSeconds: Int = 300
    
    fun executeModule(
        moduleVersion: ModuleVersion,
        inputData: Any?,
        pipelineId: String,
        runId: String,
        moduleType: ModuleType,
        connectorConfig: Map<String, Any?>? = null
    ): ModuleExecutionOutput {
        val containerName = "dt-${pipelineId}-${runId}-${moduleType.name.lowercase()}-${UUID.randomUUID()}"
        
        LOG.infof("Executing module %s in container %s", moduleVersion.module?.name, containerName)
        
        return try {
            val code = moduleVersion.code ?: throw IllegalStateException("Module code is empty")
            val requirements = moduleVersion.requirements ?: ""
            
            val output = runContainer(
                containerName = containerName,
                code = code,
                requirements = requirements,
                inputData = inputData,
                moduleType = moduleType,
                connectorConfig = connectorConfig
            )
            
            cleanupContainer(containerName)
            
            val parsedResult = parseResultPayload(output.stdout)
            ModuleExecutionOutput(
                result = parsedResult,
                stdout = output.stdout,
                stderr = output.stderr,
                exitCode = output.exitCode,
                durationMs = output.durationMs
            )
        } catch (e: ModuleExecutionException) {
            LOG.errorf("Module execution failed: %s", e.message)
            cleanupContainer(containerName)
            throw e
        } catch (e: Exception) {
            LOG.errorf("Module execution failed: %s", e.message)
            cleanupContainer(containerName)
            throw ModuleExecutionException(
                message = "Module execution failed: ${e.message}",
                cause = e
            )
        }
    }

    private fun parseResultPayload(stdout: String): Any? {
        val trimmed = stdout.trim()
        if (trimmed.isBlank()) return null

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
        val candidate = lines.lastOrNull() ?: return trimmed
        return try {
            objectMapper.readValue(candidate, Any::class.java)
        } catch (_: Exception) {
            try {
                objectMapper.readValue(trimmed, Any::class.java)
            } catch (_: Exception) {
                trimmed
            }
        }
    }
    
    private fun runContainer(
        containerName: String,
        code: String,
        requirements: String,
        inputData: Any?,
        moduleType: ModuleType,
        connectorConfig: Map<String, Any?>?
    ): RawContainerResult {
        val scriptContent = buildModuleScript(code, moduleType)
        
        val dockerArgs = mutableListOf(
            "docker", "run",
            "--rm",
            "--name", containerName
        )

        if (allowNetwork) {
            dockerArgs.addAll(listOf("--network", networkName))
        } else {
            dockerArgs.addAll(listOf("--network", "none"))
        }

        dockerArgs.addAll(listOf(
            "--user", "1000:1000",
            "--read-only",
            "--tmpfs", "/tmp",
            "--cpu-shares", (cpuLimit.toDouble() * 1024).toInt().toString(),
            "--memory", memoryLimit,
            "-e", "MODULE_CODE=$scriptContent",
            "-e", "MODULE_REQUIREMENTS=$requirements",
            "-e", "MODULE_TYPE=${moduleType.name}"
        ))
        
        inputData?.let {
            dockerArgs.add("-e")
            dockerArgs.add("INPUT_DATA=${it.toString()}")
        }

        connectorConfig?.takeIf { it.isNotEmpty() }?.let {
            dockerArgs.add("-e")
            dockerArgs.add("CONNECTOR_CONFIG=${objectMapper.writeValueAsString(it)}")
        }
        
        dockerArgs.add(baseImage)
        dockerArgs.add("python3")
        dockerArgs.add("-c")
        dockerArgs.add("import sys; exec(__import__('base64').b64decode(__import__('os').environ['MODULE_CODE']).decode())")
        
        return try {
            val processBuilder = ProcessBuilder(dockerArgs)
            val process = processBuilder.start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            fun startCollector(reader: BufferedReader, sink: StringBuilder): Thread {
                val thread = Thread {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        sink.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
                thread.start()
                return thread
            }

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            val stdoutThread = startCollector(stdoutReader, stdoutBuilder)
            val stderrThread = startCollector(stderrReader, stderrBuilder)

            val startedAt = System.currentTimeMillis()
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                throw ModuleExecutionException("Module execution timed out after $timeoutSeconds seconds")
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val durationMs = System.currentTimeMillis() - startedAt

            val exitCode = process.exitValue()
            val stdout = stdoutBuilder.toString().trim()
            val stderr = stderrBuilder.toString().trim()

            if (exitCode != 0) {
                throw ModuleExecutionException(
                    message = "Container exited with code $exitCode",
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode
                )
            }

            RawContainerResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            if (e is ModuleExecutionException) throw e
            throw ModuleExecutionException("Failed to run container: ${e.message}", cause = e)
        }
    }
    
    private fun buildModuleScript(code: String, moduleType: ModuleType): String {
        // Provide helper functions for modules in the execution environment.
        // alert_fn and query_fn are no-op stubs here; the platform can upgrade them to call back via APIs or messaging.
        val helperCode = """
            |import json
            |import os
            |import inspect
            |
            |def alert_fn(severity='info', message='', payload=None):
            |    # Placeholder: platform should intercept this output and handle alert dispatch.
            |    print(json.dumps({'__alert__': {'severity': severity, 'message': message, 'payload': payload}}))
            |
            |def query_fn(query):
            |    # Placeholder for querying platform stored data
            |    return {}
            |
            |def _connector_data():
            |    try:
            |        return json.loads(os.environ.get('CONNECTOR_CONFIG', '{}'))
            |    except Exception:
            |        return {}
            |
            |def _supports_args(fn, count):
            |    try:
            |        sig = inspect.signature(fn)
            |        params = [p for p in sig.parameters.values() if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
            |        has_varargs = any(p.kind == p.VAR_POSITIONAL for p in sig.parameters.values())
            |        if has_varargs:
            |            return True
            |        return len(params) >= count
            |    except Exception:
            |        return False
        """.trimMargin()
        
        val script = when (moduleType) {
            ModuleType.IMPORT -> """
                |import sys
                |import json
                |$helperCode
                |connector_data = _connector_data()
                |$code
                |if 'connect' in dir() and callable(connect):
                |    connect(connector_data) if _supports_args(connect, 1) else connect()
                |result = {}
                |if 'fetch' in dir() and callable(fetch):
                |    result = fetch(connector_data) if _supports_args(fetch, 1) else fetch()
                |print(json.dumps(result))
            """.trimMargin()
            
            ModuleType.PROCESSING -> """
                |import sys
                |import json
                |$helperCode
                |input_data = json.loads(__import__('os').environ.get('INPUT_DATA', '{}'))
                |connector_data = _connector_data()
                |$code
                |result = input_data
                |if 'process' in dir() and callable(process):
                |    if _supports_args(process, 2):
                |        result = process(input_data, connector_data)
                |    elif _supports_args(process, 1):
                |        result = process(input_data)
                |    else:
                |        result = process()
                |print(json.dumps(result))
            """.trimMargin()
            
            ModuleType.ALERT -> """
                |import sys
                |import json
                |$helperCode
                |input_data = json.loads(__import__('os').environ.get('INPUT_DATA', '{}'))
                |connector_data = _connector_data()
                |$code
                |result = False
                |if 'evaluate' in dir() and callable(evaluate):
                |    if _supports_args(evaluate, 2):
                |        result = evaluate(input_data, connector_data)
                |    elif _supports_args(evaluate, 1):
                |        result = evaluate(input_data)
                |    else:
                |        result = evaluate()
                |print(json.dumps({"alert_fired": result}))
            """.trimMargin()
            
            ModuleType.EXPORT -> """
                |import sys
                |import json
                |$helperCode
                |input_data = json.loads(__import__('os').environ.get('INPUT_DATA', '{}'))
                |connector_data = _connector_data()
                |$code
                |if 'connect' in dir() and callable(connect):
                |    connect(connector_data) if _supports_args(connect, 1) else connect()
                |if 'send' in dir() and callable(send):
                |    if _supports_args(send, 2):
                |        send(input_data, connector_data)
                |    elif _supports_args(send, 1):
                |        send(input_data)
                |    else:
                |        send()
                |if 'disconnect' in dir() and callable(disconnect):
                |    disconnect()
                |print(json.dumps({"status": "sent"}))
            """.trimMargin()
        }
        
        return java.util.Base64.getEncoder().encodeToString(script.toByteArray())
    }
    
    private fun cleanupContainer(containerName: String) {
        try {
            val process = ProcessBuilder("docker", "rm", "-f", containerName)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        } catch (e: Exception) {
            LOG.warnf("Failed to cleanup container %s: %s", containerName, e.message)
        }
    }
    
    fun stopAllContainers() {
        LOG.info("Stopping all DataTurntable containers")
        
        try {
            val process = ProcessBuilder("docker", "ps", "-aq", "-f", "name=dt-")
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val containerIds = reader.readLines()
            
            containerIds.forEach { containerId ->
                if (containerId.isNotBlank()) {
                    ProcessBuilder("docker", "rm", "-f", containerId)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                }
            }
        } catch (e: Exception) {
            LOG.errorf("Failed to stop containers: %s", e.message)
        }
    }
}
