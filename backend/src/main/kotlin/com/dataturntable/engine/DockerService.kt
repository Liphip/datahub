package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.module.ModelArtifactService
import com.dataturntable.execution.ExecutionLimits
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

private val isWindows = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

/**
 * Builds a `KEY=value` argument for `docker run -e`. On Windows, `java.lang.ProcessBuilder`
 * launches the child process via `CreateProcess`, whose argument re-quoting silently drops
 * embedded double quotes that aren't escaped first (verified: an unescaped `-e
 * INPUT_DATA={"value":1}` arrives at the container as `{value:1}`, invalid JSON, so a module's
 * `json.loads()` silently fails and it sees an empty payload instead of failing loudly). This
 * affects every `-e` value that can contain a `"` — JSON payloads and, worse, arbitrary user
 * module source code. POSIX `exec` (Linux prod/CI) passes argv literally and needs no escaping.
 * Shared by DockerService and WarmContainerPool, the two places that shell out to `docker run`.
 */
internal fun envArg(key: String, value: String): String {
    val safeValue = if (isWindows) value.replace("\"", "\\\"") else value
    return "$key=$safeValue"
}

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
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()

    // Thread pool for blocking I/O reads from warm containers
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "dt-docker-io").also { it.isDaemon = true }
    }

    @Inject
    lateinit var warmPool: WarmContainerPool

    @Inject
    lateinit var modelArtifactService: ModelArtifactService
    
    @ConfigProperty(name = "dataturntable.docker.module.base-image", defaultValue = "python:3.14.7-slim-trixie")
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

    @ConfigProperty(name = "dataturntable.docker.resources.pids-limit", defaultValue = "128")
    var pidsLimit: Int = 128

    @ConfigProperty(name = "dataturntable.docker.resources.max-output-bytes", defaultValue = "1048576")
    var maxOutputBytes: Int = 1_048_576
    
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
        connectorMeta: Map<String, Any?>? = null,
        /** Per-request limits bypass the reusable pool so no job inherits another job's policy. */
        limits: ExecutionLimits? = null
    ): ModuleExecutionOutput {
        val code         = moduleVersion.code ?: throw IllegalStateException("Module code is empty")
        val requirements = moduleVersion.requirements ?: ""
        val scriptB64    = buildModuleScript(code, moduleType)
        val envMap       = buildEnvMap(inputData, moduleType, connectorMeta, moduleVersion.config, moduleVersion)

        // ── Try warm pool first ────────────────────────────────────────────────
        val warm = if (limits == null) warmPool.acquire(requirements) else null
        if (warm != null) {
            LOG.debugf("Warm container hit: %s (module: %s)", warm.name, moduleVersion.module?.name)
            return try {
                val output = runJobInWarmContainer(warm, scriptB64, envMap)
                warmPool.release(warm, true)
                val parsedResult = finishResult(parseResultPayload(output.stdout), moduleType, moduleVersion, runId)
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
                scriptB64 = scriptB64,
                requirements = requirements,
                envMap = envMap,
                limits = limits ?: configuredLimits(),
            )
            cleanupContainer(containerName)

            // Proactively spawn a warm container for future runs of this module
            if (requirements.isNotBlank()) {
                io.submit {
                    val c = warmPool.spawn(requirements)
                    if (c != null) warmPool.release(c, true)
                }
            }

            val parsedResult = finishResult(parseResultPayload(output.stdout), moduleType, moduleVersion, runId)
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
        moduleConfig: String?,
        moduleVersion: ModuleVersion? = null,
    ): Map<String, String> {
        val map = mutableMapOf("MODULE_TYPE" to moduleType.name)
        // For MERGE nodes, inputData is a List (serialised as a JSON array); otherwise a Map or primitive.
        inputData?.let { map["INPUT_DATA"] = objectMapper.writeValueAsString(it) }
        // Non-sensitive connector metadata only; credentials are never passed to the container.
        connectorMeta?.takeIf { it.isNotEmpty() }?.let {
            map["CONNECTOR_META"] = objectMapper.writeValueAsString(it)
        }
        // Module version config (JSON), passed to the module class constructor.
        moduleConfig?.takeIf { it.isNotBlank() }?.let { map["MODULE_CONFIG"] = it }
        // MOD-15/18: the current version of every named model this module has saved, preloaded
        // once before the container starts (there is no live channel back to a running module).
        if (moduleType == ModuleType.PROCESSING) {
            moduleVersion?.module?.id?.let { moduleId ->
                val artifacts = modelArtifactService.preloadLatestForModule(moduleId)
                if (artifacts.isNotEmpty()) map["MODEL_ARTIFACTS"] = objectMapper.writeValueAsString(artifacts)
            }
        }
        return map
    }

    /**
     * MOD-15: persists any model a PROCESSING module saved this execution (returned under the
     * reserved `_savedModels` key by `save_model_fn`, mirroring how ALERT modules return
     * `_DT_ALERTS` for the platform to dispatch afterward) and strips that key so it never
     * reaches the next pipeline node as ordinary output data.
     */
    private fun finishResult(parsedResult: Any?, moduleType: ModuleType, moduleVersion: ModuleVersion, runId: String): Any? {
        if (moduleType != ModuleType.PROCESSING || parsedResult !is Map<*, *>) return parsedResult
        val saved = parsedResult["_savedModels"] as? Map<*, *>
        val moduleId = moduleVersion.module?.id
        val tenantId = moduleVersion.module?.tenant?.id
        if (!saved.isNullOrEmpty() && moduleId != null && tenantId != null) {
            saved.forEach { (name, data) ->
                val n = name?.toString() ?: return@forEach
                val b64 = data?.toString() ?: return@forEach
                try {
                    modelArtifactService.save(tenantId, moduleId, n, b64, runId)
                } catch (e: Exception) {
                    LOG.errorf("Failed to save model artifact '%s' for module %s: %s", n, moduleId, e.message)
                }
            }
        }
        return if (parsedResult.containsKey("_savedModels")) parsedResult.filterKeys { it != "_savedModels" } else parsedResult
    }

    /**
     * Parse the structured result from container stdout.
     * Platform scripts emit the result as `__RESULT__:<json>` on its own line.
     * Everything else in stdout is user print() output.
     */
    private fun parseResultPayload(stdout: String): Any? {
        val trimmed = stdout.trim()
        if (trimmed.isBlank()) return null

        // The platform script emits exactly one sentinel line; no sentinel means no result.
        val sentinelLine = trimmed.lines()
            .lastOrNull { it.trim().startsWith("__RESULT__:") }
            ?: return null
        val json = sentinelLine.trim().removePrefix("__RESULT__:")
        return try {
            objectMapper.readValue(json, Any::class.java)
        } catch (_: Exception) { json }
    }
    
    /**
     * Processes the named request, event, or operation.
     */
    private fun runContainer(
        containerName: String,
        scriptB64: String,
        requirements: String,
        envMap: Map<String, String>,
        limits: ExecutionLimits,
    ): RawContainerResult {
        val dockerArgs = mutableListOf(
            "docker", "run",
            "--rm",
            "--name", containerName
        )

        // Network access is opt-in at deployment level; dependency installation must use
        // prebuilt artifacts in the secure default profile rather than opening egress.
        dockerArgs.addAll(listOf("--network", networkMode(limits)))

        dockerArgs.addAll(sandboxArguments(limits))

        dockerArgs.addAll(listOf(
            "-e", envArg("MODULE_CODE", scriptB64),
            "-e", envArg("MODULE_REQUIREMENTS", requirements),
        ))
        envMap.forEach { (key, value) -> dockerArgs.addAll(listOf("-e", envArg(key, value))) }
        
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
                        appendBoundedOutput(sink, line, limits.maxOutputBytes)
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
            val finished = process.waitFor(limits.timeoutSeconds.toLong(), TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                throw ModuleExecutionException("Module execution timed out after ${limits.timeoutSeconds} seconds")
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

    internal fun networkMode(limits: ExecutionLimits): String =
        if (allowNetwork && limits.allowNetwork) networkName else "none"

    /** Pure policy seam: assert the security-critical Docker flags without a daemon. */
    internal fun sandboxArguments(limits: ExecutionLimits): List<String> = listOf(
            "--user", "1000:1000",
            "--read-only",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges:true",
            "--pids-limit", limits.maxPids.toString(),
            "--ulimit", "nofile=1024:1024",
            "--tmpfs", "/tmp:size=${limits.temporaryDiskMiB}m",
            "--tmpfs", "/.cache",          // pip cache dir (user 1000 can't write to / otherwise)
            "--cpus", limits.cpuCores.toString(),
            "--memory", "${limits.memoryMiB}m"
        )

    /** Keeps captured process output within the caller's hard cap, including newlines. */
    internal fun appendBoundedOutput(sink: StringBuilder, line: String, maxOutputBytes: Int) {
        val remaining = maxOutputBytes - sink.length
        if (remaining <= 0) return
        sink.append(line.take(remaining))
        if (sink.length < maxOutputBytes) sink.append('\n')
    }

    private fun configuredLimits() = ExecutionLimits(
        timeoutSeconds = timeoutSeconds,
        cpuCores = cpuLimit.toDoubleOrNull() ?: 1.0,
        memoryMiB = memoryLimit.trim().removeSuffix("m").removeSuffix("M").toIntOrNull() ?: 512,
        maxPids = pidsLimit,
        maxOutputBytes = maxOutputBytes,
        allowNetwork = allowNetwork
    )
    
    /**
     * Implements `buildModuleScript` for this source unit.
     */
    internal fun buildModuleScript(code: String, moduleType: ModuleType): String {
        // Shared runtime helpers injected into every module container.
        // KEY DESIGN PRINCIPLE:
        //   IMPORT modules receive already-fetched data (INPUT_DATA env var) from the platform.
        //   EXPORT modules return shaped data; the platform delivers it via the connector.
        //   Modules never make network calls — all I/O is the platform's responsibility.
        val helperCode = """
            |import json, os, inspect
            |
            |_DT_ALERTS = []
            |
            |def alert_fn(severity='warning', message='', payload=None, title=None, channel=None, cooldown_seconds=None):
            |    # Fire an alert. Collected alerts are returned with the ALERT node result and
            |    # dispatched by the platform. channel = alert channel id or name (default: node/tenant default).
            |    _DT_ALERTS.append({'severity': severity, 'message': message, 'payload': payload,
            |                       'title': title, 'channel': channel, 'cooldownSeconds': cooldown_seconds})
            |
            |def query_fn(query):
            |    # Query platform stored data. Returns empty dict in current runtime.
            |    return {}
            |
            |import base64, pickle
            |_DT_SAVED_MODELS = {}
            |
            |def _model_artifacts():
            |    try:
            |        return json.loads(os.environ.get('MODEL_ARTIFACTS', '{}'))
            |    except Exception:
            |        return {}
            |
            |def save_model_fn(name, model):
            |    # Persists a fitted model under `name` (MOD-15). The platform stores whatever
            |    # pickle.dumps produces without inspecting or executing it, as a new version;
            |    # a later execution's load_model_fn(name) sees it (MOD-18/19). Only takes effect
            |    # if this module's process() returns a dict — see the functional spec, §5.6.
            |    _DT_SAVED_MODELS[name] = base64.b64encode(pickle.dumps(model)).decode('ascii')
            |
            |def load_model_fn(name, version=None):
            |    # Returns the model this module last saved under `name` (unpickled), or None if
            |    # none was ever saved. `version` pins a specific past version if the platform
            |    # preloaded it; pinning a version this execution did NOT start with is not
            |    # supported (no live channel back to the platform mid-execution — MOD-16).
            |    if version is not None:
            |        raise NotImplementedError(
            |            'load_model_fn(name, version=...) is not supported inside a running '
            |            'module; only the latest version is preloaded per execution (MOD-16).')
            |    encoded = _model_artifacts().get(name)
            |    if encoded is None:
            |        return None
            |    return pickle.loads(base64.b64decode(encoded))
            |
            |def _connector_meta():
            |    # Non-sensitive connector metadata (type, name, topic, etc.) for informational use only.
            |    try:
            |        return json.loads(os.environ.get('CONNECTOR_META', '{}'))
            |    except Exception:
            |        return {}
            |
            |def _module_config():
            |    # Module version config (JSON object) set in the module editor.
            |    try:
            |        cfg = json.loads(os.environ.get('MODULE_CONFIG', '{}'))
            |        return cfg if isinstance(cfg, dict) else {}
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
            |            inst = cls(_module_config()) if _has_config_init(cls) else cls()
            |        except Exception as e:
            |            raise RuntimeError(f"Cannot instantiate {class_name}: {e}") from e
            |        m = getattr(inst, method_name, None)
            |        if callable(m):
            |            return m
            |    fn = globals().get(method_name)
            |    return fn if callable(fn) else None
            |
            |def _has_config_init(cls):
            |    # True only if the class defines its own __init__ accepting a config argument.
            |    if cls.__init__ is object.__init__:
            |        return False
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
                |result = _call(fn, input_data, query_fn, save_model_fn, load_model_fn) if fn else input_data
                |if _DT_SAVED_MODELS:
                |    if isinstance(result, dict):
                |        result = dict(result)
                |        result['_savedModels'] = _DT_SAVED_MODELS
                |    else:
                |        print(f"save_model_fn was called but process() returned a "
                |              f"{type(result).__name__}, not a dict — the model was NOT saved. "
                |              f"Return a dict to save a model (functional spec, §5.6).", file=_dt_sys.stderr)
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
                |# This is a TERMINAL node; alerts collected via alert_fn are returned to the platform.
                |import sys as _dt_sys
                |input_data = _load_input()
                |$code
                |fn = _resolve('AlertModule', 'evaluate')
                |result = _call(fn, input_data, alert_fn, query_fn) if fn else False
                |_dt_sys.stdout.flush()
                |print('__RESULT__:' + json.dumps({"alert_fired": bool(result) or bool(_DT_ALERTS), "alerts": _DT_ALERTS}, default=str))
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
