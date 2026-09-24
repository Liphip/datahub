package com.dataturntable.engine

import com.dataturntable.config.RuntimeRoleConfig
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A long-running warm container that accepts module jobs via stdin/stdout.
 *
 * Protocol:
 *  - On startup: installs requirements (if any), then writes "__READY__\n" to stdout.
 *  - Job in:  single JSON line {"code":"<b64>","env":{...},"timeout":300}
 *  - Job out: single JSON line {"stdout":"...","stderr":"...","exit_code":0}
 *  - Termination: write "__EXIT__\n" to stdin, or destroy process.
 */
/**
 * Defines the class `WarmContainer`, the contract implemented by this source unit.
 */
data class WarmContainer(
    val name: String,
    val process: Process,
    val writer: PrintWriter,
    val reader: BufferedReader,
    /** Requirements string this container was initialised with (empty = base). */
    val requirements: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val alive: Boolean get() = process.isAlive

    /**
     * Removes, stops, or releases the requested state.
     */
    fun destroy() {
        runCatching { writer.println("__EXIT__"); writer.flush() }
        runCatching { process.destroyForcibly() }
    }
}

/**
 * Manages a pool of pre-warmed Docker containers to avoid cold-start latency on each run.
 *
 * Two tiers:
 *  1. Base pool – containers with no requirements installed; ready for any module that has no
 *     pip dependencies. Always kept pre-warmed to [basePoolSize].
 *  2. Module pool – containers keyed by SHA-256(requirements); up to [moduleCacheMax] per key.
 *     Built lazily (on first use) and maintained for future runs of the same module version.
 *
 * On acquire the caller gets an exclusive container. On release it is returned to the appropriate
 * pool (or discarded if unhealthy / pool is full). The base pool is always refilled asynchronously.
 */
@ApplicationScoped
/**
 * Defines the class `WarmContainerPool`, the contract implemented by this source unit.
 */
class WarmContainerPool {

    private val LOG = Logger.getLogger(WarmContainerPool::class.java)

    // Reuse an unbounded cached thread pool for async spawning and I/O reads.
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "dt-warm-pool").also { it.isDaemon = true }
    }

    @Inject
    lateinit var meterRegistry: MeterRegistry

    @Inject
    lateinit var pipelineMetrics: PipelineMetrics

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @ConfigProperty(name = "dataturntable.docker.module.base-image", defaultValue = "python:3.14.7-slim-trixie")
    lateinit var baseImage: String

    @ConfigProperty(name = "dataturntable.docker.resources.cpu-limit", defaultValue = "1.0")
    lateinit var cpuLimit: String

    @ConfigProperty(name = "dataturntable.docker.resources.memory-limit", defaultValue = "512m")
    lateinit var memoryLimit: String

    /** Set to false to disable the pool entirely (e.g. in dev/test). */
    @ConfigProperty(name = "dataturntable.docker.pool.enabled", defaultValue = "true")
    var poolEnabled: Boolean = true

    /** Number of base (no-requirements) containers to keep pre-warmed. */
    @ConfigProperty(name = "dataturntable.docker.pool.base-size", defaultValue = "3")
    var basePoolSize: Int = 3

    /** Maximum cached containers per unique requirements set. */
    @ConfigProperty(name = "dataturntable.docker.pool.module-cache-max", defaultValue = "2")
    var moduleCacheMax: Int = 2

    // ── Pools ──────────────────────────────────────────────────────────────────

    private val basePool  = ConcurrentLinkedDeque<WarmContainer>()
    private val reqPools  = ConcurrentHashMap<String, ConcurrentLinkedDeque<WarmContainer>>()

    /** Tracks how many base containers are currently being spawned to prevent over-spawning. */
    private val spawningCount = AtomicInteger(0)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    fun onStart(@Observes ev: StartupEvent) {
        // Register Prometheus gauges for pool monitoring
        meterRegistry.gauge("dt_warm_pool_base_size", basePool) { it.size.toDouble() }
        meterRegistry.gauge("dt_warm_pool_base_target", this) { basePoolSize.toDouble() }
        meterRegistry.gauge("dt_warm_pool_spawning", spawningCount) { it.get().toDouble() }
        meterRegistry.gauge("dt_warm_pool_module_pools", reqPools) { it.size.toDouble() }
        meterRegistry.gauge("dt_warm_pool_module_containers", reqPools) {
            it.values.sumOf { pool -> pool.size }.toDouble()
        }

        if (!runtimeRoleConfig.ownsPipelineRuntime()) {
            LOG.infof("WarmContainerPool startup skipped for runtime role %s", runtimeRoleConfig.role)
            return
        }
        if (!poolEnabled) { LOG.info("WarmContainerPool disabled"); return }
        LOG.infof("WarmContainerPool: pre-warming %d base containers", basePoolSize)
        repeat(basePoolSize) { io.submit { spawnIntoBase() } }
    }

    /**
     * Implements `onStop` for this source unit.
     */
    fun onStop(@Observes ev: ShutdownEvent) {
        drainAndDestroy(basePool)
        reqPools.values.forEach { drainAndDestroy(it) }
        io.shutdownNow()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Try to acquire a ready container for [requirements].
     * - Empty requirements → base pool only.
     * - Non-empty requirements → module-specific pool; does NOT fall back to base pool
     *   (a base container hasn't installed those requirements).
     * Returns null if no matching container is available.
     */
    fun acquire(requirements: String): WarmContainer? {
        if (!poolEnabled) return null
        val container = if (requirements.isBlank()) {
            basePool.poll()
        } else {
            reqPools[hash(requirements)]?.poll()
        }
        return when {
            container == null    -> {
                pipelineMetrics.poolAcquireCounter("miss").increment()
                null
            }
            !container.alive     -> {
                pipelineMetrics.poolAcquireCounter("dead").increment()
                ensureBasePool(); null
            }
            else                 -> {
                pipelineMetrics.poolAcquireCounter("hit").increment()
                container
            }
        }
    }

    /**
     * Return a container after use.
     * - [healthy] = false: discard immediately (e.g. non-zero exit, parse error).
     * - healthy base container: return to base pool if not full.
     * - healthy module container: return to its requirements pool if not full.
     * Always ensures the base pool is topped up asynchronously.
     */
    fun release(container: WarmContainer, healthy: Boolean) {
        if (!healthy || !container.alive) {
            container.destroy()
            if (container.requirements.isBlank()) ensureBasePool()
            return
        }
        if (container.requirements.isBlank()) {
            if (basePool.size < basePoolSize) basePool.offer(container) else container.destroy()
        } else {
            val pool = reqPools.getOrPut(hash(container.requirements)) { ConcurrentLinkedDeque() }
            if (pool.size < moduleCacheMax) pool.offer(container) else container.destroy()
        }
        ensureBasePool()
    }

    /**
     * Spawn a new warm container with [requirements] pre-installed.
     * Blocks until the container signals "__READY__" (may be slow if pip install runs).
     * Returns null on failure.
     */
    fun spawn(requirements: String = "", readyTimeoutSeconds: Long = 120): WarmContainer? {
        if (!poolEnabled) return null
        val name = "dt-warm-${UUID.randomUUID().toString().take(8)}"
        val runnerB64 = buildRunnerB64()

        // Containers that need pip install require network access to reach PyPI.
        // Containers with no requirements run fully isolated.
        val needsNetwork = requirements.isNotBlank()

        val args = mutableListOf(
            "docker", "run", "-i", "--rm", "--name", name,
            "--network", if (needsNetwork) "bridge" else "none",
            "--user", "1000:1000",
            "--read-only",
            "--tmpfs", "/tmp:size=256m",   // pip --target /tmp/packages goes here
            "--tmpfs", "/.cache",          // pip cache dir (user 1000 can't write to / otherwise)
            "--cpu-shares", (cpuLimit.toDouble() * 1024).toInt().toString(),
            "--memory", memoryLimit,
            "-e", "__DT_RUNNER__=$runnerB64",
        )
        if (needsNetwork) {
            args.addAll(listOf("-e", "MODULE_REQUIREMENTS=$requirements"))
        }
        args.addAll(listOf(
            baseImage,
            "python3", "-c",
            "import base64,os; exec(base64.b64decode(os.environ['__DT_RUNNER__']).decode())",
        ))

        return try {
            val proc    = ProcessBuilder(args).start()
            val writer  = PrintWriter(proc.outputStream, true)
            val reader  = BufferedReader(InputStreamReader(proc.inputStream))

            // Drain stderr so the container doesn't block on a full pipe buffer
            io.submit { proc.errorStream.bufferedReader().forEachLine { _ -> } }

            // Wait for __READY__ (pip install can be slow for heavy packages)
            val readyLine = io.submit<String?> { reader.readLine() }
                .get(readyTimeoutSeconds, TimeUnit.SECONDS)

            if (readyLine != "__READY__") {
                LOG.warnf("Warm container %s sent unexpected ready signal: %s", name, readyLine)
                proc.destroyForcibly()
                return null
            }
            LOG.debugf("Warm container ready: %s (reqs: %s)", name,
                if (requirements.isBlank()) "none" else requirements.take(60))
            WarmContainer(name, proc, writer, reader, requirements)
        } catch (e: Exception) {
            LOG.warnf("Failed to spawn warm container %s: %s", name, e.message)
            null
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    private fun spawnIntoBase() {
        spawningCount.incrementAndGet()
        try {
            val c = spawn("")
            if (c != null) basePool.offer(c)
            else LOG.warn("Failed to pre-warm a base container")
        } finally {
            spawningCount.decrementAndGet()
        }
    }

    /**
     * Implements `ensureBasePool` for this source unit.
     */
    private fun ensureBasePool() {
        // Account for containers currently being spawned to avoid over-spawning
        val effective = basePool.size + spawningCount.get()
        val deficit = basePoolSize - effective
        if (deficit > 0) repeat(deficit) { io.submit { spawnIntoBase() } }
    }

    /**
     * Implements `drainAndDestroy` for this source unit.
     */
    private fun drainAndDestroy(pool: ConcurrentLinkedDeque<WarmContainer>) {
        while (pool.isNotEmpty()) pool.poll()?.destroy()
    }

    /**
     * Evaluates the named capability or state predicate.
     */
    fun hash(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the base64-encoded Python runner that runs inside every warm container.
     *
     * Startup:
     *   1. Optionally install requirements from MODULE_REQUIREMENTS env var into /tmp/packages.
     *   2. Write "__READY__\n" to stdout.
     *
     * Job loop (one JSON line per job from stdin):
     *   - Decode code from base64.
     *   - Merge env vars from job with container env.
     *   - Run in subprocess (captures stdout/stderr cleanly).
     *   - Write JSON result line to stdout.
     */
    private fun buildRunnerB64(): String {
        // Using trimIndent + raw strings so indentation doesn't end up in the Python.
        val script = """
import sys, json, os, base64, subprocess

_reqs = os.environ.get('MODULE_REQUIREMENTS', '').strip()
if _reqs:
    _r = subprocess.run(
        [sys.executable, '-m', 'pip', 'install',
         '--target', '/tmp/packages', '-q', '--no-warn-script-location']
        + [r for r in _reqs.splitlines() if r.strip()],
        capture_output=True, text=True,
    )
    if _r.returncode != 0:
        sys.stdout.write(json.dumps({'error': 'pip install failed: ' + _r.stderr}) + '\n')
        sys.stdout.flush()
        sys.exit(1)
    sys.path.insert(0, '/tmp/packages')

sys.stdout.write('__READY__\n')
sys.stdout.flush()

for _line in sys.stdin:
    _s = _line.strip()
    if not _s:
        continue
    if _s == '__EXIT__':
        break
    _j = json.loads(_s)
    _code = base64.b64decode(_j['code']).decode()
    _env  = {**os.environ, **_j.get('env', {})}
    try:
        _p = subprocess.run(
            [sys.executable, '-c', _code], env=_env,
            capture_output=True, text=True, timeout=_j.get('timeout', 300),
        )
        sys.stdout.write(json.dumps({
            'stdout': _p.stdout, 'stderr': _p.stderr, 'exit_code': _p.returncode,
        }) + '\n')
    except subprocess.TimeoutExpired:
        sys.stdout.write(json.dumps({'stdout': '', 'stderr': 'Module timed out', 'exit_code': 124}) + '\n')
    except Exception as _e:
        sys.stdout.write(json.dumps({'stdout': '', 'stderr': str(_e), 'exit_code': 1}) + '\n')
    sys.stdout.flush()
""".trimIndent()
        return Base64.getEncoder().encodeToString(script.toByteArray())
    }
}
