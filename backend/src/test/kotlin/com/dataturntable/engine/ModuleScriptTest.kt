package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Runs the generated module wrapper with a local python3 (skipped when none is installed). */
class ModuleScriptTest {

    private val objectMapper = ObjectMapper()

    /**
     * True only for a real interpreter, not merely a zero exit code. On Windows the Microsoft Store
     * app-execution alias installs a `python3.exe` stub that prints "Python wurde nicht gefunden…"
     * and still exits 0, so an exit-code-only check wrongly concludes Python exists: the tests then
     * run against the stub and fail with that message instead of skipping. A prefix check on "Python"
     * is not enough either — the stub's own message starts with that word — so require the version
     * banner itself. This makes the tests skip correctly on a machine without Python, as the class
     * comment promises.
     */
    private fun pythonAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS) && PYTHON_VERSION.matches(output)
    }.getOrDefault(false)

    private companion object {
        /** `Python 3.13.7` — the stub prints prose that merely starts with the word "Python". */
        private val PYTHON_VERSION = Regex("""Python \d+\.\d+.*""")
    }

    private data class Run(val exitCode: Int, val output: String)

    private fun exec(code: String, type: ModuleType, input: Any, moduleConfig: String? = null): Run {
        val script = String(Base64.getDecoder().decode(DockerService().buildModuleScript(code, type)))
        // Hand the script to python as a file, not as `python3 -c <script>`. On Windows the JVM has
        // to rebuild a command line, and the double quotes inside the generated script are mangled
        // on the way (an f-string like f"Module ..." arrives as fModule, giving a bogus
        // "SyntaxError: '(' was never closed"). Passing a file preserves the script byte for byte on
        // every platform. Production does not use `-c` either: DockerService passes the script
        // base64-encoded in __DT_BOOT__ and execs it inside the container.
        val scriptFile = Files.createTempFile("dtt-module-script", ".py").toFile()
        try {
            scriptFile.writeText(script)
            val process = ProcessBuilder("python3", scriptFile.absolutePath)
                .also { pb ->
                    pb.environment()["INPUT_DATA"] = objectMapper.writeValueAsString(input)
                    moduleConfig?.let { pb.environment()["MODULE_CONFIG"] = it }
                }
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "module script timed out")
            return Run(process.exitValue(), out)
        } finally {
            scriptFile.delete()
        }
    }

    private fun run(code: String, type: ModuleType, input: Any, moduleConfig: String? = null): Map<*, *> {
        val out = exec(code, type, input, moduleConfig).output
        val line = out.lines().lastOrNull { it.startsWith("__RESULT__:") } ?: error("no result line in:\n$out")
        return objectMapper.readValue(line.removePrefix("__RESULT__:"), Map::class.java)
    }

    @Test
    fun alertModuleReturnsAlertsRaisedWithAlertFn() {
        assumeTrue(pythonAvailable())
        val code = """
            |class AlertModule:
            |    def evaluate(self, data, alert_fn=None, query_fn=None):
            |        if data['value'] > 80:
            |            alert_fn(severity='critical', title='Too hot', message='value high', payload=data, channel='ops', cooldown_seconds=60)
            |            return True
            |        return False
        """.trimMargin()

        val fired = run(code, ModuleType.ALERT, mapOf("value" to 91))
        assertEquals(true, fired["alert_fired"])
        val alert = (fired["alerts"] as List<*>).single() as Map<*, *>
        assertEquals("critical", alert["severity"])
        assertEquals("Too hot", alert["title"])
        assertEquals("ops", alert["channel"])
        assertEquals(60, alert["cooldownSeconds"])
        assertEquals(mapOf("value" to 91), alert["payload"])

        val quiet = run(code, ModuleType.ALERT, mapOf("value" to 20))
        assertEquals(false, quiet["alert_fired"])
        assertEquals(emptyList<Any>(), quiet["alerts"])
    }

    @Test
    fun moduleClassWithoutInitIsCalled() {
        assumeTrue(pythonAvailable())
        val code = """
            |class ProcessingModule:
            |    def process(self, data):
            |        return {"doubled": data["value"] * 2}
        """.trimMargin()
        assertEquals(mapOf("doubled" to 42), run(code, ModuleType.PROCESSING, mapOf("value" to 21)))
    }

    @Test
    fun moduleConfigIsPassedToConstructor() {
        assumeTrue(pythonAvailable())
        val code = """
            |class AlertModule:
            |    def __init__(self, config=None):
            |        self.threshold = (config or {}).get('threshold', 80)
            |    def evaluate(self, data, alert_fn=None):
            |        if data['value'] > self.threshold:
            |            alert_fn(message='over')
            |        return False
        """.trimMargin()
        val result = run(code, ModuleType.ALERT, mapOf("value" to 50), moduleConfig = """{"threshold": 40}""")
        assertEquals(true, result["alert_fired"])
    }

    @Test
    fun constructorFailureFailsTheModule() {
        assumeTrue(pythonAvailable())
        val code = """
            |class ProcessingModule:
            |    def __init__(self, config):
            |        raise ValueError('bad config')
            |    def process(self, data):
            |        return data
        """.trimMargin()
        val result = exec(code, ModuleType.PROCESSING, mapOf("value" to 1))
        assertTrue(result.exitCode != 0, "expected non-zero exit, got output:\n${result.output}")
        assertTrue(result.output.contains("Cannot instantiate ProcessingModule"), result.output)
    }
}
