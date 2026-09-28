package com.dataturntable.examples

import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.test.TestAuthHelpers
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import jakarta.enterprise.inject.Any as AnyBean

/**
 * The load-forecast example of docs/examples/model-forecast, run end to end with the module and
 * the pipeline definitions from that directory: a nightly pipeline fits a line to a day's hourly
 * readings and saves it as a model artifact, a second pipeline applies the saved line to each new
 * reading, and a retrain is picked up without a restart. No ML server is involved.
 *
 * Needs a real Docker daemon, because a PROCESSING module runs in a container; skipped without one,
 * like the other module-execution tests.
 */
@QuarkusTest
@org.junit.jupiter.api.condition.EnabledIf("com.dataturntable.test.DockerAvailability#available")
class ModelForecastExampleTest {

    @Inject lateinit var queue: PipelineRunQueue
    @Inject lateinit var consumer: PipelineConsumer
    @Inject @AnyBean lateinit var messaging: InMemoryConnector

    private val mapper = ObjectMapper()
    private val examples: Path = listOf(
        Path.of("../docs/examples/model-forecast"), Path.of("docs/examples/model-forecast")
    ).first { Files.isDirectory(it) }
    private lateinit var server: HttpServer
    private val training = CopyOnWriteArrayList<Any?>()
    private val forecasts = CopyOnWriteArrayList<Any?>()
    private lateinit var token: String
    private lateinit var tenantId: String
    private lateinit var trainingPipeline: UUID
    private lateinit var inferencePipeline: UUID

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        listOf("/training" to training, "/forecast" to forecasts).forEach { (path, sink) ->
            server.createContext(path) { ex ->
                sink += mapper.readValue(ex.requestBody.readAllBytes(), Any::class.java)
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
        }
        server.start()
        token = TestAuthHelpers.createAdminToken()
        val root = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        tenantId = api().body(mapOf("name" to "Forecast Beispiel ${UUID.randomUUID()}", "parentId" to root))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")

        // One module with two roles, because a model artifact is module-scoped: the module that
        // saves a model is the only one that can load it. See the example README.
        val moduleId = api().body(mapOf("name" to "load-forecast", "moduleType" to "PROCESSING",
            "description" to "Fits a line to a day's readings, or applies the saved line to one reading"))
            .post("/api/internal/v1/modules?tenantId=$tenantId").then().statusCode(201)
            .extract().jsonPath().getString("id")
        val code = Files.readString(examples.resolve("load-forecast-module.py"))
        val versionId = api().body(mapOf("code" to code))
            .post("/api/internal/v1/modules/$moduleId/versions").then().statusCode(201)
            .extract().jsonPath().getString("id")

        trainingPipeline = pipeline("load-training.pipeline.json",
            "forecastModuleVersionId" to versionId,
            "trainingConnectorId" to restOutput("Training report", "/training"))
        inferencePipeline = pipeline("load-inference.pipeline.json",
            "forecastModuleVersionId" to versionId,
            "forecastConnectorId" to restOutput("Forecast sink", "/forecast"))
        messaging.sink<String>("pipeline-commands").clear()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun api() = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)

    /** Creates and activates a pipeline from its example file; the API validates the graph. */
    private fun pipeline(file: String, vararg placeholders: Pair<String, String>): UUID {
        var json = Files.readString(examples.resolve(file))
        placeholders.forEach { (key, value) -> json = json.replace("\${$key}", value) }
        val id = api().body(json).post("/api/internal/v1/pipelines?tenantId=$tenantId")
            .then().statusCode(201).extract().jsonPath().getString("id")
        api().body(mapOf("isActive" to true)).patch("/api/internal/v1/pipelines/$id").then().statusCode(200)
        return UUID.fromString(id)
    }

    private fun restOutput(name: String, path: String): String = api()
        .body(mapOf("name" to name, "connectorType" to "REST", "direction" to "OUTPUT",
            "config" to mapOf("baseUrl" to "http://127.0.0.1:${server.address.port}", "path" to path, "method" to "POST")))
        .post("/api/internal/v1/connectors?tenantId=$tenantId").then().statusCode(201)
        .extract().jsonPath().getString("id")

    /** Queues a record as the ingest API would and runs every queued message. */
    private fun ingest(pipelineId: UUID, record: Map<String, Any?>) {
        val sink = messaging.sink<String>("pipeline-commands")
        sink.clear()
        queue.enqueue(pipelineId, TriggerType.DATA, record)
        sink.received().forEach {
            val event = consumer.consume(it.payload)
            assertTrue(event.success || event.stage == "skipped", "run failed: ${event.error}")
        }
        sink.clear()
    }

    /** The EXPORT node delivers asynchronously, so wait for the expected number of payloads. */
    private fun await(sink: CopyOnWriteArrayList<Any?>, expected: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (sink.size < expected && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    /** loadKwh = 100 + 10 * hour, i.e. a perfect line: slope 10, intercept 100. */
    private fun readings(offset: Double = 0.0, slope: Double = 10.0) =
        (0..5).map { mapOf("hour" to it, "loadKwh" to 100.0 + slope * it + offset) }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.path(vararg keys: String): Any? = keys.fold(this) { cur, k -> (cur as? Map<String, Any?>)?.get(k) }

    @Test
    fun `a model is trained once and applied by a later, independent run`() {
        // Nothing trained yet: the inference pipeline reports it instead of failing the record,
        // so readings that arrive before the first nightly run are not lost.
        ingest(inferencePipeline, mapOf("station" to "DRD-17", "hour" to 6, "loadKwh" to 160.0))
        await(forecasts, 1)
        assertEquals("missing", forecasts[0].path("modelState"), "no model yet: ${forecasts[0]}")

        ingest(trainingPipeline, mapOf("station" to "DRD-17", "date" to "2026-09-27",
            "readings" to readings()))
        await(training, 1)
        assertEquals("train", training[0].path("role"))
        assertEquals(6, (training[0].path("observations") as Number).toInt())
        assertEquals(10.0, (training[0].path("slope") as Number).toDouble(), 1e-9)
        assertEquals(100.0, (training[0].path("intercept") as Number).toDouble(), 1e-9)

        // A completely separate run, from a different pipeline, applies the saved line.
        ingest(inferencePipeline, mapOf("station" to "DRD-17", "hour" to 6, "loadKwh" to 160.0))
        await(forecasts, 2)
        val applied = forecasts[1]
        assertEquals("applied", applied.path("modelState"), "model should be applied: $applied")
        assertEquals(160.0, (applied.path("forecastNextHourKwh") as Number).toDouble(), 1e-6)
        assertEquals("DRD-17", applied.path("station"))
    }

    @Test
    fun `a retrain is picked up by the next inference run without a restart`() {
        ingest(trainingPipeline, mapOf("station" to "DRD-17", "date" to "2026-09-27", "readings" to readings()))
        ingest(trainingPipeline, mapOf("station" to "DRD-17", "date" to "2026-09-28",
            "readings" to readings(offset = 20.0, slope = 12.0)))
        await(training, 2)

        // No pipeline change, no redeploy: the same inference pipeline now uses version 2,
        // whose line is loadKwh = 120 + 12 * hour, so hour 6 forecasts 192.
        ingest(inferencePipeline, mapOf("station" to "DRD-17", "hour" to 6, "loadKwh" to 192.0))
        await(forecasts, 1)
        assertEquals("applied", forecasts[0].path("modelState"))
        assertEquals(192.0, (forecasts[0].path("forecastNextHourKwh") as Number).toDouble(), 1e-6)
    }

    @Test
    fun `a training run on too few readings fails loudly instead of saving a broken model`() {
        val sink = messaging.sink<String>("pipeline-commands")
        sink.clear()
        queue.enqueue(trainingPipeline, TriggerType.DATA, mapOf("station" to "DRD-17", "date" to "2026-09-27",
            "readings" to listOf(mapOf("hour" to 0, "loadKwh" to 100.0))))
        val event = consumer.consume(sink.received().single().payload)
        assertEquals(false, event.success, "a one-point fit must fail the run")
        await(training, 0)
        assertTrue(training.isEmpty(), "nothing may be delivered when training fails")
        // Give the (asynchronous) delivery a moment to prove it really does not arrive.
        TimeUnit.MILLISECONDS.sleep(300)
        assertTrue(training.isEmpty())
    }
}
