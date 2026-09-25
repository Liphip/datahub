package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Runs the generated module wrapper with a local python3 (skipped when none is installed). */
class ModuleScriptTest {

    private val objectMapper = ObjectMapper()

    private fun pythonAvailable(): Boolean =
        runCatching { ProcessBuilder("python3", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)

    private data class Run(val exitCode: Int, val output: String)

    private fun exec(code: String, type: ModuleType, input: Any, moduleConfig: String? = null): Run {
        val script = String(Base64.getDecoder().decode(DockerService().buildModuleScript(code, type)))
        val process = ProcessBuilder("python3", "-c", script)
            .also { pb ->
                pb.environment()["INPUT_DATA"] = objectMapper.writeValueAsString(input)
                moduleConfig?.let { pb.environment()["MODULE_CONFIG"] = it }
            }
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "module script timed out")
        return Run(process.exitValue(), out)
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
