package com.dataturntable.engine

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Installs a module's Python requirements once per distinct requirements set, outside the
 * module's own container (MOD-05, NFR-S3).
 *
 * A module runs without network access. Installing its requirements needs one, so the install
 * happens in a separate, short-lived container that has a network but none of the module's
 * input, configuration, secrets or code, writes into a Docker volume named after the hash of the
 * requirements, and exits. Every run of a module with those requirements then mounts that volume
 * read-only into its network-less container. By default only wheels are accepted
 * (`--only-binary=:all:`): unpacking a wheel runs no code, so nothing a requirement's author wrote
 * executes while a network is attached.
 *
 * The volume is complete once `/pkgs/site` exists: pip installs into a temporary directory that is
 * renamed into place only on success, so an interrupted or failed install never looks complete,
 * and two installers racing for the same volume (two executor threads, or two replicas on one
 * Docker daemon) cannot leave a half-written directory behind — the second rename fails and is
 * discarded.
 */
@ApplicationScoped
class ModuleDependencyCache {
    private val log = Logger.getLogger(ModuleDependencyCache::class.java)

    @ConfigProperty(name = "dataturntable.docker.module.base-image", defaultValue = "python:3.14.7-slim-trixie")
    lateinit var baseImage: String

    /** Docker network used only while requirements are installed; it must reach the package index. */
    @ConfigProperty(name = "dataturntable.docker.dependency-install.network", defaultValue = "bridge")
    lateinit var installNetwork: String

    /** Alternative package index, e.g. an internal mirror (pip `--index-url`). */
    @ConfigProperty(name = "dataturntable.docker.dependency-install.index-url")
    var indexUrl: Optional<String> = Optional.empty()

    /** Outbound proxy for the install container only (`HTTPS_PROXY`/`HTTP_PROXY`). */
    @ConfigProperty(name = "dataturntable.docker.dependency-install.proxy")
    var proxy: Optional<String> = Optional.empty()

    /** Allow sdists, whose build scripts run while the install container has a network. */
    @ConfigProperty(name = "dataturntable.docker.dependency-install.allow-source-builds", defaultValue = "false")
    var allowSourceBuilds: Boolean = false

    @ConfigProperty(name = "dataturntable.docker.dependency-install.timeout-seconds", defaultValue = "600")
    var timeoutSeconds: Long = 600

    /** Volumes known to be complete, so a run does not start an extra container to check. */
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Normalised requirement lines: trimmed, without blank lines and comments. */
    internal fun normalise(requirements: String): List<String> =
        requirements.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    internal fun volumeName(requirements: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalise(requirements).joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return "dt-pkgs-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * The volume holding [requirements], installed if it is not yet. Null when there is nothing
     * to install. Throws [ModuleExecutionException] with pip's output when the install fails.
     */
    fun ensure(requirements: String): String? {
        val lines = normalise(requirements)
        if (lines.isEmpty()) return null
        val volume = volumeName(requirements)
        if (volume in ready) return volume
        synchronized(locks.computeIfAbsent(volume) { Any() }) {
            if (volume in ready) return volume
            if (isComplete(volume)) {
                ready += volume
                return volume
            }
            log.infof("Installing module requirements into %s: %s", volume, lines.joinToString(", ").take(200))
            val startedAt = System.currentTimeMillis()
            docker(prepareArgs(volume), 60)
            val install = docker(installArgs(volume, lines), timeoutSeconds)
            if (install.exitCode != 0) {
                throw ModuleExecutionException(
                    message = "Installing module requirements failed (exit ${install.exitCode}); see stderr",
                    stdout = install.stdout,
                    stderr = install.stderr.ifBlank { install.stdout },
                    exitCode = install.exitCode,
                )
            }
            if (!isComplete(volume)) throw ModuleExecutionException("Installing module requirements left $volume incomplete")
            log.infof("Module requirements installed into %s in %d ms", volume, System.currentTimeMillis() - startedAt)
            ready += volume
            return volume
        }
    }

    /**
     * `docker run` arguments that mount [volume] for a module run: read-only, on its own path, and
     * on `PYTHONPATH` so that every Python process in the container sees it — including the child
     * process a warm container starts per job, which inherits the container's environment.
     */
    internal fun mountArgs(volume: String): List<String> =
        listOf("-v", "$volume:$MOUNT_PATH:ro", "-e", "PYTHONPATH=$MOUNT_PATH/site")

    /**
     * A new volume is owned by root. The only step that runs as root does nothing but hand it to
     * the unprivileged module user; it runs no requirement and has no network.
     */
    internal fun prepareArgs(volume: String): List<String> = listOf(
        "docker", "run", "--rm", "--network", "none",
        "--user", "0:0", "--cap-drop", "ALL", "--cap-add", "CHOWN", "--security-opt", "no-new-privileges:true",
        "-v", "$volume:/pkgs", baseImage, "chown", "1000:1000", "/pkgs",
    )

    internal fun installArgs(volume: String, requirements: List<String>): List<String> {
        val args = mutableListOf(
            "docker", "run", "--rm", "--network", installNetwork,
            "--user", "1000:1000", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--pids-limit", "256", "--tmpfs", "/tmp:size=1024m", "-e", "HOME=/tmp", "-e", "TMPDIR=/tmp",
            "-v", "$volume:/pkgs",
            "-e", envArg("DT_REQUIREMENTS", requirements.joinToString("\n")),
            "-e", "DT_ONLY_BINARY=${if (allowSourceBuilds) "0" else "1"}",
        )
        indexUrl.filter { it.isNotBlank() }.ifPresent { args += listOf("-e", envArg("PIP_INDEX_URL", it)) }
        proxy.filter { it.isNotBlank() }.ifPresent {
            args += listOf("-e", envArg("HTTPS_PROXY", it), "-e", envArg("HTTP_PROXY", it))
        }
        args += listOf(baseImage, "python3", "-c", "import base64;exec(base64.b64decode('$INSTALLER_B64').decode())")
        return args
    }

    private fun isComplete(volume: String): Boolean =
        docker(listOf("docker", "run", "--rm", "--network", "none", "--user", "1000:1000", "--read-only",
            "-v", "$volume:/pkgs:ro", baseImage, "test", "-d", "/pkgs/site"), 60).exitCode == 0

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private fun docker(args: List<String>, timeoutSeconds: Long): Result {
        val process = ProcessBuilder(args).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readers = listOf(process.inputStream to stdout, process.errorStream to stderr).map { (stream, sink) ->
            Thread { stream.bufferedReader().forEachLine { if (sink.length < MAX_OUTPUT) sink.appendLine(it) } }.apply { start() }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw ModuleExecutionException("Installing module requirements timed out after $timeoutSeconds seconds")
        }
        readers.forEach { it.join(1000) }
        return Result(process.exitValue(), stdout.toString().trim(), stderr.toString().trim())
    }

    companion object {
        const val MOUNT_PATH = "/opt/dt-packages"
        private const val MAX_OUTPUT = 64 * 1024

        /** Runs inside the install container. `DT_PKGS_ROOT` exists so the script can be tested without Docker. */
        internal val INSTALLER = """
import os, sys, subprocess, uuid, shutil
root = os.environ.get('DT_PKGS_ROOT', '/pkgs')
target = os.path.join(root, 'site')
if os.path.isdir(target):
    sys.exit(0)
reqs = [r for r in os.environ.get('DT_REQUIREMENTS', '').splitlines() if r.strip()]
tmp = os.path.join(root, '.tmp-' + uuid.uuid4().hex)
cmd = [sys.executable, '-m', 'pip', 'install', '--target', tmp, '--no-cache-dir', '--disable-pip-version-check',
       '--no-warn-script-location', '-q']
if os.environ.get('DT_ONLY_BINARY', '1') == '1':
    cmd.append('--only-binary=:all:')
r = subprocess.run(cmd + reqs, capture_output=True, text=True)
if r.returncode != 0:
    shutil.rmtree(tmp, ignore_errors=True)
    sys.stderr.write(r.stderr or r.stdout)
    sys.exit(r.returncode)
try:
    os.rename(tmp, target)
except OSError:
    shutil.rmtree(tmp, ignore_errors=True)  # another installer finished first
""".trimIndent()

        private val INSTALLER_B64: String = Base64.getEncoder().encodeToString(INSTALLER.toByteArray(StandardCharsets.UTF_8))
    }
}
