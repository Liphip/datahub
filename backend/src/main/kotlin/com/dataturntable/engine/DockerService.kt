package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Defines the class `ModuleExecutionOutput`, the contract implemented by this source unit.
 */
data class ModuleExecutionOutput(
    val result: Any?,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long
)

/**
 * Defines the class `ModuleExecutionException`, the contract implemented by this source unit.
 */
class ModuleExecutionException(
    message: String,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Defines the class `RawContainerResult`, the contract implemented by this source unit.
 */
private data class RawContainerResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long
)

@ApplicationScoped
/**
 * Defines the class `DockerService`, the contract implemented by this source unit.
 */
class DockerService {

    private val LOG = Logger.getLogger(DockerService::class.java)
    private val objectMapper = ObjectMapper()

    // Thread pool for blocking I/O reads from warm containers
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "dt-docker-io").also { it.isDaemon = true }
    }

    @Inject
    lateinit var warmPool: WarmContainerPool
    
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
    
    /**
     * Processes the named request, event, or operation.
     */
    fun executeModule(
        moduleVersion: ModuleVersion,
        inputData: Any?,
        pipelineId: String,
        runId: String,
        moduleType: ModuleType,
        /** Non-sensitive connector metadata (name, type, topic, etc.) for informational use in module. No credentials. */
        connectorMeta: Map<String, Any?>? = null
    ): ModuleExecutionOutput {
        val code         = moduleVersion.code ?: throw IllegalStateException("Module code is empty")
        val requirements = moduleVersion.requirements ?: ""
        val scriptB64    = buildModuleScript(code, moduleType)
        val envMap       = buildEnvMap(inputData, moduleType, connectorMeta)

        // ── Try warm pool first ────────────────────────────────────────────────
        val warm = warmPool.acquire(requirements)
        if (warm != null) {
            LOG.debugf("Warm container hit: %s (module: %s)", warm.name, moduleVersion.module?.name)
            return try {
                val output = runJobInWarmContainer(warm, scriptB64, envMap)
                warmPool.release(warm, true)
                val parsedResult = parseResultPayload(output.stdout)
                ModuleExecutionOutput(parsedResult, output.stdout, output.stderr, output.exitCode, output.durationMs)
            } catch (e: ModuleExecutionException) {
                warmPool.release(warm, false)
                LOG.errorf("Warm module execution failed: %s", e.message)
                throw e
            } catch (e: Exception) {
                warmPool.release(warm, false)
                throw ModuleExecutionException("Module execution failed: ${e.message}", cause = e)
            }
        }

        // ── Fallback: ephemeral container ──────────────────────────────────────
        val containerName = "dt-${pipelineId}-${runId}-${moduleType.name.lowercase()}-${UUID.randomUUID()}"
        LOG.infof("Warm pool miss — ephemeral container: %s (module: %s)", containerName, moduleVersion.module?.name)

        return try {
            val output = runContainer(
                containerName = containerName,
                code = code,
                requirements = requirements,
                inputData = inputData,
                moduleType = moduleType,
                connectorMeta = connectorMeta,
            )
            cleanupContainer(containerName)

            // Proactively spawn a warm container for future runs of this module
            if (requirements.isNotBlank()) {
                io.submit {
                    val c = warmPool.spawn(requirements)
                    if (c != null) warmPool.release(c, true)
                }
            }

            val parsedResult = parseResultPayload(output.stdout)
            ModuleExecutionOutput(parsedResult, output.stdout, output.stderr, output.exitCode, output.durationMs)
        } catch (e: ModuleExecutionException) {
            LOG.errorf("Module execution failed: %s", e.message)
            cleanupContainer(containerName)
            throw e
        } catch (e: Exception) {
            LOG.errorf("Module execution failed: %s", e.message)
            cleanupContainer(containerName)
            throw ModuleExecutionException("Module execution failed: ${e.message}", cause = e)
        }
    }

    /**
     * Send a job to a warm container and read back the result.
     * The job carries the base64-encoded module script and env vars; the runner
     * executes it in a subprocess and returns stdout/stderr/exit_code as JSON.
     */
    private fun runJobInWarmContainer(
        container: WarmContainer,
        scriptB64: String,
        envMap: Map<String, String>,
    ): RawContainerResult {
        val job = mapOf("code" to scriptB64, "env" to envMap, "timeout" to timeoutSeconds)
        container.writer.println(objectMapper.writeValueAsString(job))

        val startedAt = System.currentTimeMillis()

        val resultLine = try {
            io.submit<String?> { container.reader.readLine() }
                .get(timeoutSeconds.toLong() + 30, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            container.destroy()
            throw ModuleExecutionException("Module execution timed out after $timeoutSeconds seconds")
        }

        if (resultLine == null) {
            throw ModuleExecutionException("Warm container closed connection unexpectedly")
        }

        val durationMs = System.currentTimeMillis() - startedAt

        @Suppress("UNCHECKED_CAST")
        val result   = objectMapper.readValue(resultLine, Map::class.java) as Map<String, Any?>
        val exitCode = (result["exit_code"] as? Int) ?: 1
        val stdout   = (result["stdout"] as? String) ?: ""
        val stderr   = (result["stderr"] as? String) ?: ""

        if (exitCode != 0) {
            throw ModuleExecutionException(
                message  = "Container exited with code $exitCode",
                stdout   = stdout,
                stderr   = stderr,
                exitCode = exitCode,
            )
        }
        return RawContainerResult(stdout, stderr, exitCode, durationMs)
    }

    /** Build the env map passed to warm container jobs (no MODULE_CODE — script is in job.code). */
    /**
     * Implements `buildEnvMap` for this source unit.
     */
    private fun buildEnvMap(
        inputData: Any?,
        moduleType: ModuleType,
        connectorMeta: Map<String, Any?>?,
    ): Map<String, String> {
        val map = mutableMapOf("MODULE_TYPE" to moduleType.name)
        inputData?.let { map["INPUT_DATA"] = objectMapper.writeValueAsString(it) }
        connectorMeta?.takeIf { it.isNotEmpty() }?.let {
            map["CONNECTOR_META"] = objectMapper.writeValueAsString(it)
        }
        return map
    }

    /**
     * Parse the structured result from container stdout.
     * Platform scripts emit the result as `__RESULT__:<json>` on its own line.
     * Everything else in stdout is user print() output.
     */
    private fun parseResultPayload(stdout: String): Any? {
        val trimmed = stdout.trim()
        if (trimmed.isBlank()) return null

        // Prefer the sentinel line emitted by the platform script
        val sentinelLine = trimmed.lines()
            .lastOrNull { it.trim().startsWith("__RESULT__:") }
        if (sentinelLine != null) {
            val json = sentinelLine.trim().removePrefix("__RESULT__:")
            return try {
                objectMapper.readValue(json, Any::class.java)
            } catch (_: Exception) { json }
        }

        // Fallback: try last non-blank line as JSON (legacy / error paths)
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
        val candidate = lines.lastOrNull() ?: return trimmed
        return try {
            objectMapper.readValue(candidate, Any::class.java)
        } catch (_: Exception) {
            try { objectMapper.readValue(trimmed, Any::class.java) } catch (_: Exception) { trimmed }
        }
    }
    
    /**
     * Processes the named request, event, or operation.
     */
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

        // Containers that need pip install require network access to reach PyPI.
        // Containers with no requirements run fully isolated.
        val needsNetwork = requirements.isNotBlank()
        dockerArgs.addAll(listOf("--network", if (needsNetwork) "bridge" else "none"))

        dockerArgs.addAll(listOf(
            "--user", "1000:1000",
            "--read-only",
            "--tmpfs", "/tmp:size=256m",
            "--tmpfs", "/.cache",          // pip cache dir (user 1000 can't write to / otherwise)
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
        
        // Bootstrap: optionally install requirements, then exec the module script.
        // Packages are installed to /tmp/packages (the only writable space under --read-only).
        val bootstrap = """
import sys, os, base64, subprocess
_reqs = os.environ.get('MODULE_REQUIREMENTS', '').strip()
if _reqs:
    _r = subprocess.run(
        [sys.executable, '-m', 'pip', 'install',
         '--target', '/tmp/packages', '-q', '--no-warn-script-location']
        + [r for r in _reqs.splitlines() if r.strip()],
        capture_output=True, text=True,
    )
    if _r.returncode != 0:
        sys.stderr.write('pip install failed: ' + _r.stderr)
        sys.exit(1)
    sys.path.insert(0, '/tmp/packages')
exec(base64.b64decode(os.environ['MODULE_CODE']).decode())
""".trimIndent()

        // Pass bootstrap via env var (-e must come before image name in docker run)
        dockerArgs.addAll(listOf(
            "-e", "__DT_BOOT__=${Base64.getEncoder().encodeToString(bootstrap.toByteArray())}",
        ))

        dockerArgs.addAll(listOf(
            baseImage,
            "python3", "-c",
            "import base64,os; exec(base64.b64decode(os.environ['__DT_BOOT__']).decode())",
        ))
        
        return try {
            val processBuilder = ProcessBuilder(dockerArgs)
            val process = processBuilder.start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            /**
             * Initializes, connects, or acquires the named runtime resource.
             */
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
    
    /**
     * Implements `buildModuleScript` for this source unit.
     */
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
                |import sys as _dt_sys
                |input_data = _load_input()
                |connector_meta = _connector_meta()
                |$code
                |fn = _resolve('ImportModule', 'parse')
                |result = _call(fn, input_data, connector_meta) if fn else input_data
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps(result))
            """.trimMargin()

            ModuleType.PROCESSING -> """
                |$helperCode
                |# input_data = output of the single upstream node (dict or primitive).
                |import sys as _dt_sys
                |input_data = _load_input()
                |$code
                |fn = _resolve('ProcessingModule', 'process')
                |result = _call(fn, input_data, query_fn) if fn else input_data
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps(result))
            """.trimMargin()

            ModuleType.MERGE -> """
                |$helperCode
                |# inputs = list of outputs from ALL upstream nodes (always a list, ≥2 items).
                |import sys as _dt_sys
                |inputs = _load_inputs_list()
                |$code
                |fn = _resolve('MergeModule', 'merge')
                |result = _call(fn, inputs) if fn else inputs[0] if inputs else {}
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps(result))
            """.trimMargin()

            ModuleType.ALERT -> """
                |$helperCode
                |# input_data = upstream node output. Call alert_fn() to fire an alert.
                |# Return True if fired, False otherwise. This is a TERMINAL node.
                |import sys as _dt_sys
                |input_data = _load_input()
                |$code
                |fn = _resolve('AlertModule', 'evaluate')
                |result = _call(fn, input_data, alert_fn, query_fn) if fn else False
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps({"alert_fired": bool(result)}))
            """.trimMargin()

            ModuleType.EXPORT -> """
                |$helperCode
                |# input_data = shaped payload from upstream. Return the final outbound dict.
                |# The platform delivers it to the external system — do NOT make network calls here.
                |import sys as _dt_sys
                |input_data = _load_input()
                |connector_meta = _connector_meta()
                |$code
                |fn = _resolve('ExportModule', 'shape')
                |result = _call(fn, input_data, connector_meta) if fn else input_data
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps(result))
            """.trimMargin()

            ModuleType.DB_LOAD -> """
                |$helperCode
                |# DB_LOAD modules are platform-managed data-source modules.
                |# metadata() may describe supported query parameters; load() returns rows.
                |import sys as _dt_sys
                |query = _load_input()
                |$code
                |meta_fn = _resolve('DbLoadModule', 'metadata')
                |load_fn = _resolve('DbLoadModule', 'load')
                |metadata = _call(meta_fn) if meta_fn else {}
                |result = _call(load_fn, query, {"metadata": metadata, "connector_meta": _connector_meta()}) if load_fn else []
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps(result))
            """.trimMargin()

            ModuleType.DB_SAVE -> """
                |$helperCode
                |# DB_SAVE modules are platform-managed data-sink modules.
                |# metadata() may describe write semantics; prepare() normalizes rows to persist.
                |import sys as _dt_sys
                |input_data = _load_input()
                |$code
                |meta_fn = _resolve('DbSaveModule', 'metadata')
                |prepare_fn = _resolve('DbSaveModule', 'prepare')
                |metadata = _call(meta_fn) if meta_fn else {}
                |result = _call(prepare_fn, input_data, {"metadata": metadata, "connector_meta": _connector_meta()}) if prepare_fn else input_data
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps(result))
            """.trimMargin()
        }

        return java.util.Base64.getEncoder().encodeToString(script.toByteArray())
    }
    
    /**
     * Implements `cleanupContainer` for this source unit.
     */
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
    
    /**
     * Removes, stops, or releases the requested state.
     */
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
