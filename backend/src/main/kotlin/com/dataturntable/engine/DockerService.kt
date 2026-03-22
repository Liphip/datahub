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
        /** Non-sensitive connector metadata (name, type, topic, etc.) for informational use in module. No credentials. */
        connectorMeta: Map<String, Any?>? = null
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
                connectorMeta = connectorMeta
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
        connectorMeta: Map<String, Any?>?
    ): RawContainerResult {
        val scriptContent = buildModuleScript(code, moduleType)

        val dockerArgs = mutableListOf(
            "docker", "run",
            "--rm",
            "--name", containerName
        )

        // Modules never need network access — the platform handles all I/O via ConnectorRuntimeService.
        dockerArgs.addAll(listOf("--network", "none"))

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

        // For MERGE nodes, inputData is a List — serialise it as a JSON array.
        // For all other nodes it is a Map or primitive — serialise as-is.
        inputData?.let {
            val serialised = objectMapper.writeValueAsString(it)
            dockerArgs.add("-e")
            dockerArgs.add("INPUT_DATA=$serialised")
        }

        // Non-sensitive connector metadata (connector type, name, topic, path, etc.)
        // Credentials are NEVER passed to the container.
        connectorMeta?.takeIf { it.isNotEmpty() }?.let {
            dockerArgs.add("-e")
            dockerArgs.add("CONNECTOR_META=${objectMapper.writeValueAsString(it)}")
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
        // Shared runtime helpers injected into every module container.
        // KEY DESIGN PRINCIPLE:
        //   IMPORT modules receive already-fetched data (INPUT_DATA env var) from the platform.
        //   EXPORT modules return shaped data; the platform delivers it via the connector.
        //   Modules never make network calls — all I/O is the platform's responsibility.
        val helperCode = """
            |import json, os, inspect
            |
            |def alert_fn(severity='info', message='', payload=None):
            |    # Fire an alert. The platform intercepts __alert__ lines in stdout.
            |    print(json.dumps({'__alert__': {'severity': severity, 'message': message, 'payload': payload}}))
            |
            |def query_fn(query):
            |    # Query platform stored data. Returns empty dict in current runtime.
            |    return {}
            |
            |def _connector_meta():
            |    # Non-sensitive connector metadata (type, name, topic, etc.) for informational use only.
            |    try:
            |        return json.loads(os.environ.get('CONNECTOR_META', '{}'))
            |    except Exception:
            |        return {}
            |
            |def _load_input():
            |    try:
            |        return json.loads(os.environ.get('INPUT_DATA', '{}'))
            |    except Exception:
            |        return {}
            |
            |def _load_inputs_list():
            |    try:
            |        raw = os.environ.get('INPUT_DATA', '[]')
            |        parsed = json.loads(raw)
            |        return parsed if isinstance(parsed, list) else [parsed]
            |    except Exception:
            |        return []
            |
            |def _call(fn, *args):
            |    # Call fn with as many args as its signature accepts (up to len(args)).
            |    try:
            |        sig = inspect.signature(fn)
            |        params = [p for p in sig.parameters.values()
            |                  if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
            |        has_var = any(p.kind == p.VAR_POSITIONAL for p in sig.parameters.values())
            |        n = len(args) if has_var else min(len(params), len(args))
            |        return fn(*args[:n])
            |    except Exception as e:
            |        raise RuntimeError(f"Module call failed: {e}") from e
            |
            |def _resolve(class_name, method_name):
            |    # Find method_name on class_name instance, or as a bare function.
            |    cls = globals().get(class_name)
            |    if inspect.isclass(cls):
            |        try:
            |            inst = cls(_connector_meta()) if _has_config_init(cls) else cls()
            |            m = getattr(inst, method_name, None)
            |            if callable(m):
            |                return m
            |        except Exception:
            |            pass
            |    fn = globals().get(method_name)
            |    return fn if callable(fn) else None
            |
            |def _has_config_init(cls):
            |    try:
            |        sig = inspect.signature(cls.__init__)
            |        params = [p for p in sig.parameters.values()
            |                  if p.name != 'self' and p.kind in
            |                  (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD, p.VAR_POSITIONAL)]
            |        return len(params) > 0
            |    except Exception:
            |        return False
        """.trimMargin()

        val script = when (moduleType) {
            ModuleType.IMPORT -> """
                |$helperCode
                |# INPUT_DATA = raw payload already fetched by the platform connector.
                |# The module's job is ONLY to parse/transform/validate it.
                |# Do NOT make network calls here — the platform handles all I/O.
                |input_data = _load_input()
                |connector_meta = _connector_meta()
                |$code
                |fn = _resolve('ImportModule', 'parse')
                |result = _call(fn, input_data, connector_meta) if fn else input_data
                |print(json.dumps(result))
            """.trimMargin()

            ModuleType.PROCESSING -> """
                |$helperCode
                |# input_data = output of the single upstream node (dict or primitive).
                |input_data = _load_input()
                |$code
                |fn = _resolve('ProcessingModule', 'process')
                |result = _call(fn, input_data, query_fn) if fn else input_data
                |print(json.dumps(result))
            """.trimMargin()

            ModuleType.MERGE -> """
                |$helperCode
                |# inputs = list of outputs from ALL upstream nodes (always a list, ≥2 items).
                |inputs = _load_inputs_list()
                |$code
                |fn = _resolve('MergeModule', 'merge')
                |result = _call(fn, inputs) if fn else inputs[0] if inputs else {}
                |print(json.dumps(result))
            """.trimMargin()

            ModuleType.ALERT -> """
                |$helperCode
                |# input_data = upstream node output. Call alert_fn() to fire an alert.
                |# Return True if fired, False otherwise. This is a TERMINAL node.
                |input_data = _load_input()
                |$code
                |fn = _resolve('AlertModule', 'evaluate')
                |result = _call(fn, input_data, alert_fn, query_fn) if fn else False
                |print(json.dumps({"alert_fired": bool(result)}))
            """.trimMargin()

            ModuleType.EXPORT -> """
                |$helperCode
                |# input_data = shaped payload from upstream. Return the final outbound dict.
                |# The platform delivers it to the external system — do NOT make network calls here.
                |input_data = _load_input()
                |connector_meta = _connector_meta()
                |$code
                |fn = _resolve('ExportModule', 'shape')
                |result = _call(fn, input_data, connector_meta) if fn else input_data
                |print(json.dumps(result))
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
