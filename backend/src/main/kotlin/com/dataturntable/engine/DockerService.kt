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
            dockerArgs.add("INPUT_DATA=${objectMapper.writeValueAsString(it)}")
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
            |from __future__ import annotations
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
            |
            |def _build_module_instance(class_name, config):
            |    cls = globals().get(class_name)
            |    if not inspect.isclass(cls):
            |        return None
            |    try:
            |        init_fn = getattr(cls, '__init__', None)
            |        if init_fn and _supports_args(init_fn, 1):
            |            return cls(config)
            |        return cls()
            |    except Exception:
            |        return cls()
            |
            |def _resolve_callable(class_name, method_name, config):
            |    instance = _build_module_instance(class_name, config)
            |    if instance is not None:
            |        method = getattr(instance, method_name, None)
            |        if callable(method):
            |            return method
            |    fn = globals().get(method_name)
            |    if callable(fn):
            |        return fn
            |    return None
            |
            |def _load_input_data():
            |    try:
            |        return json.loads(os.environ.get('INPUT_DATA', '{}'))
            |    except Exception:
            |        return {}
        """.trimMargin()
        
        val script = when (moduleType) {
            ModuleType.IMPORT -> """
                |$helperCode
                |import json
                |input_data = _load_input_data()
                |connector_data = _connector_data()
                |$code
                |result = {}
                |fetch_fn = _resolve_callable('ImportModule', 'fetch', connector_data)
                |if callable(fetch_fn):
                |    if _supports_args(fetch_fn, 2):
                |        result = fetch_fn(input_data, connector_data)
                |    elif _supports_args(fetch_fn, 1):
                |        result = fetch_fn(input_data)
                |    else:
                |        result = fetch_fn()
                |print(json.dumps(result))
            """.trimMargin()
            
            ModuleType.PROCESSING -> """
                |$helperCode
                |import json
                |input_data = _load_input_data()
                |connector_data = _connector_data()
                |$code
                |result = input_data
                |process_fn = _resolve_callable('ProcessingModule', 'process', connector_data)
                |if callable(process_fn):
                |    if _supports_args(process_fn, 2):
                |        result = process_fn(input_data, connector_data)
                |    elif _supports_args(process_fn, 1):
                |        result = process_fn(input_data)
                |    else:
                |        result = process_fn()
                |print(json.dumps(result))
            """.trimMargin()
            
            ModuleType.ALERT -> """
                |$helperCode
                |import json
                |input_data = _load_input_data()
                |connector_data = _connector_data()
                |$code
                |result = False
                |evaluate_fn = _resolve_callable('AlertModule', 'evaluate', connector_data)
                |if callable(evaluate_fn):
                |    if _supports_args(evaluate_fn, 3):
                |        result = evaluate_fn(input_data, alert_fn, query_fn)
                |    elif _supports_args(evaluate_fn, 2):
                |        result = evaluate_fn(input_data, alert_fn)
                |    elif _supports_args(evaluate_fn, 1):
                |        result = evaluate_fn(input_data)
                |    else:
                |        result = evaluate_fn()
                |print(json.dumps({"alert_fired": result}))
            """.trimMargin()
            
            ModuleType.EXPORT -> """
                |$helperCode
                |import json
                |input_data = _load_input_data()
                |connector_data = _connector_data()
                |$code
                |result = input_data
                |send_fn = _resolve_callable('ExportModule', 'send', connector_data)
                |if callable(send_fn):
                |    if _supports_args(send_fn, 2):
                |        result = send_fn(input_data, connector_data)
                |    elif _supports_args(send_fn, 1):
                |        result = send_fn(input_data)
                |    else:
                |        result = send_fn()
                |print(json.dumps({"status": "sent", "result": result}))
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
