package com.dataturntable.examples

import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.test.TestAuthHelpers
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
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
import jakarta.enterprise.inject.Any as AnyBean

/**
 * The §14a EnWG example of docs/examples/energy-14a, run end to end with the pipeline
 * definitions from that directory: LoRaWAN readings are combined with meter data from SAP and
 * locations from an internal register, and delivered to a metering system and a grid
 * calculation. §14a is an example use case of the data hub, not built-in behaviour.
 */
@QuarkusTest
class Energy14aExampleTest {

    @Inject lateinit var queue: PipelineRunQueue
    @Inject lateinit var consumer: PipelineConsumer
    @Inject lateinit var em: EntityManager
    @Inject @AnyBean lateinit var messaging: InMemoryConnector

    private val mapper = ObjectMapper()
    private val examples: Path = listOf(Path.of("../docs/examples/energy-14a"), Path.of("docs/examples/energy-14a")).first { Files.isDirectory(it) }
    private lateinit var server: HttpServer
    private val metering = CopyOnWriteArrayList<Pair<String?, Any?>>()
    private val grid = CopyOnWriteArrayList<Pair<String?, Any?>>()
    private lateinit var token: String
    private lateinit var tenantId: String

    private val device = "70B3D57ED0000001"
    private val meterOld = "1ESY1160000001"
    private val meterNew = "1ESY1160000002"
    private val melo = "DE0005620000000000000000000000001"
    private val malo = "51238696781"

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        listOf("/metering" to metering, "/grid" to grid).forEach { (path, sink) ->
            server.createContext(path) { ex ->
                sink += ex.requestHeaders.getFirst("Idempotency-Key") to mapper.readValue(ex.requestBody.readAllBytes(), Any::class.java)
                ex.sendResponseHeaders(204, -1)
                ex.close()
            }
        }
        server.start()
        token = TestAuthHelpers.createAdminToken()
        val root = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        tenantId = api().body(mapOf("name" to "Stadtwerke Beispiel ${UUID.randomUUID()}", "parentId" to root))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")
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
        .post("/api/internal/v1/connectors?tenantId=$tenantId").then().log().ifValidationFails().statusCode(201).extract().jsonPath().getString("id")

    /** Queues a record as the ingest API would and runs every queued message; returns the run ids. */
    private fun ingest(pipelineId: UUID, record: Map<String, Any?>, key: String? = null): List<UUID> {
        val sink = messaging.sink<String>("pipeline-commands")
        sink.clear()
        queue.enqueue(pipelineId, TriggerType.DATA, record, idempotencyKey = key)
        return sink.received().map { message ->
            val event = consumer.consume(message.payload)
            assertTrue(event.success || event.stage == "skipped", "run failed: ${event.error}")
            UUID.fromString(event.runId)
        }.also { sink.clear() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.path(vararg keys: String): Any? = keys.fold(this) { cur, k -> (cur as? Map<String, Any?>)?.get(k) }

    @Test
    fun `readings reach metering and grid calculation with the metadata valid at their time`() {
        api().post("/api/internal/v1/metadata/templates/energy/install?tenantId=$tenantId").then().statusCode(200)

        val sap = pipeline("sap-meters.pipeline.json")
        val locations = pipeline("internal-locations.pipeline.json")
        val devices = pipeline("lorawan-devices.pipeline.json")
        val readings = pipeline("lorawan-readings.pipeline.json",
            "meteringConnectorId" to restOutput("Metering system", "/metering"),
            "gridConnectorId" to restOutput("Grid calculation", "/grid"))

        // Master data, in any order: missing targets are created and filled in later.
        ingest(devices, mapOf("devEUI" to device, "meterNumber" to meterOld, "model" to "LW-EM1", "installedAt" to "2026-01-01"))
        ingest(devices, mapOf("devEUI" to device, "meterNumber" to meterNew, "model" to "LW-EM1", "installedAt" to "2026-06-01"))
        ingest(sap, mapOf("EQUNR" to "10000001", "SERNR" to meterOld, "HERST" to "EMH", "PHASEN" to "3", "MELO" to melo, "MALO" to malo, "AB" to "2026-01-01"))
        ingest(sap, mapOf("EQUNR" to "10000002", "SERNR" to meterNew, "HERST" to "Landis+Gyr", "PHASEN" to "3", "MELO" to melo, "MALO" to malo, "AB" to "2026-06-01"))
        ingest(locations, mapOf("malo" to malo, "direction" to "consumption", "site" to "S-4711", "street" to "Hauptstr. 1",
            "postalCode" to "01067", "city" to "Dresden", "lat" to "51.05", "lon" to "13.74", "gridArea" to "ONS-Dresden-17"))

        // A reading before and one after the meter exchange on 1 June.
        ingest(readings, mapOf("devEUI" to device, "time" to "2026-05-15T12:00:00Z", "energyKwh" to 1234.5))
        val runs = ingest(readings, mapOf("devEUI" to device, "time" to "2026-07-01T12:00:00Z", "energyKwh" to 1301.25),
            key = "$device|2026-07-01T12:00:00Z")

        assertEquals(2, metering.size)
        assertEquals(2, grid.size)
        val (mayKey, may) = metering[0]
        val july = metering[1].second
        assertEquals(meterOld, may.path("meter", "ids", "meter_number"), "May reading: $may")
        assertEquals(meterNew, july.path("meter", "ids", "meter_number"))
        assertEquals("Landis+Gyr", july.path("meter", "manufacturer"))
        assertEquals(3, (july.path("meter", "phases") as Number).toInt())
        assertEquals(malo, july.path("marketLocation", "ids", "malo"))
        assertEquals("01067", july.path("location", "postalCode"))
        assertEquals("ONS-Dresden-17", grid[1].second.path("gridArea", "ids", "grid_area_id"))
        assertTrue(mayKey != null && mayKey.endsWith(":metering"), "exports carry an idempotency key: $mayKey")

        // The gateway sends the July reading again: same key, same run, no second delivery.
        val again = ingest(readings, mapOf("devEUI" to device, "time" to "2026-07-01T12:00:00Z", "energyKwh" to 1301.25),
            key = "$device|2026-07-01T12:00:00Z")
        assertEquals(runs, again)
        assertEquals(2, metering.size)
        assertEquals(2, grid.size)

        // A reading from an unknown device fails its run (kept for Retry once the device is registered).
        val unknown = messaging.sink<String>("pipeline-commands").also { it.clear() }
        queue.enqueue(readings, TriggerType.DATA, mapOf("devEUI" to "70B3D57ED00000FF", "time" to "2026-07-01T12:00:00Z", "energyKwh" to 1.0))
        val event = consumer.consume(unknown.received().single().payload)
        assertEquals(false, event.success)
        QuarkusTransaction.requiringNew().run {
            assertEquals(PipelineRunStatus.FAILED, em.find(PipelineRun::class.java, UUID.fromString(event.runId)).status)
        }
        assertEquals(2, metering.size)
    }
}
