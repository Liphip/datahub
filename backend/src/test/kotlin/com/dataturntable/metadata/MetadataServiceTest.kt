package com.dataturntable.metadata

import com.dataturntable.engine.PipelineConsumer
import com.dataturntable.engine.PipelineRunQueue
import com.dataturntable.pipeline.Pipeline
import com.dataturntable.pipeline.PipelineRun
import com.dataturntable.pipeline.PipelineRunStatus
import com.dataturntable.pipeline.TriggerType
import com.dataturntable.tenant.Tenant
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyBean

@QuarkusTest
class MetadataServiceTest {

    @Inject lateinit var metadata: MetadataService
    @Inject lateinit var nodes: MetadataNodes
    @Inject lateinit var em: EntityManager
    @Inject lateinit var queue: PipelineRunQueue
    @Inject lateinit var consumer: PipelineConsumer
    @Inject @AnyBean lateinit var messaging: InMemoryConnector

    private lateinit var tenantId: UUID
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = Instant.parse("2026-06-01T00:00:00Z")

    private val sapMeters = SaveMetadataConfig.parse(mapOf(
        "type" to "meter",
        "match" to mapOf("scheme" to "sap.equipment", "field" to "EQUNR"),
        "identifiers" to mapOf("meter_number" to "SERNR"),
        "attributes" to mapOf("manufacturer" to "HERST"),
        "relations" to listOf(mapOf("relation" to "metering_location", "type" to "metering_location", "scheme" to "melo", "field" to "MELO", "create" to true)),
    ))
    private val sapLocations = SaveMetadataConfig.parse(mapOf(
        "type" to "metering_location",
        "match" to mapOf("scheme" to "melo", "field" to "MELO"),
        "relations" to listOf(mapOf("relation" to "market_location", "type" to "market_location", "scheme" to "malo", "field" to "MALO", "create" to true)),
    ))
    private val internalLocations = SaveMetadataConfig.parse(mapOf(
        "type" to "market_location",
        "match" to mapOf("scheme" to "malo", "field" to "malo"),
        "relations" to listOf(mapOf("relation" to "location", "type" to "location", "scheme" to "location_id", "field" to "site", "create" to true)),
    ))
    private val sites = SaveMetadataConfig.parse(mapOf(
        "type" to "location",
        "match" to mapOf("scheme" to "location_id", "field" to "site"),
        "attributes" to mapOf("city" to "city", "postalCode" to "zip"),
        "relations" to listOf(mapOf("relation" to "grid_area", "type" to "grid_area", "scheme" to "grid_area_id", "field" to "area", "create" to true)),
    ))
    private val lorawan = SaveMetadataConfig.parse(mapOf(
        "type" to "lorawan_device",
        "match" to mapOf("scheme" to "lorawan.devEUI", "field" to "devEUI"),
        "relations" to listOf(mapOf("relation" to "meter", "type" to "meter", "scheme" to "meter_number", "field" to "meter")),
        "validFromField" to "from",
    ))
    private val enrichReading = EnrichConfig.parse(mapOf(
        "match" to mapOf("scheme" to "lorawan.devEUI", "field" to "devEUI"),
        "timestampField" to "ts",
        "outputs" to listOf(
            mapOf("as" to "meter", "path" to listOf("meter")),
            mapOf("as" to "malo", "path" to listOf("meter", "metering_location", "market_location")),
            mapOf("as" to "location", "path" to listOf("meter", "metering_location", "market_location", "location")),
            mapOf("as" to "gridArea", "path" to listOf("meter", "metering_location", "market_location", "location", "grid_area")),
        ),
    ))

    private val melo1 = "DE0001234567890000000000000000001"
    private val melo2 = "DE0001234567890000000000000000002"

    @BeforeEach
    fun setUp() {
        tenantId = QuarkusTransaction.requiringNew().call {
            Tenant().apply { name = "metadata-${UUID.randomUUID()}" }.also { em.persist(it) }.id!!
        }
        given().header("Authorization", "Bearer ${TestAuthHelpers.createAdminToken()}")
            .post("/api/internal/v1/metadata/templates/energy/install?tenantId=$tenantId")
            .then().statusCode(200)
        metadata.invalidateAll()
    }

    @AfterEach
    fun tearDown() {
        QuarkusTransaction.requiringNew().run {
            listOf("MetadataRelation", "MetadataIdentifier").forEach {
                em.createQuery("DELETE FROM $it x WHERE x.tenantId = :t").setParameter("t", tenantId).executeUpdate()
            }
            em.createQuery("DELETE FROM MetadataAttributeVersion a WHERE a.objectId IN (SELECT o.id FROM MetadataObject o WHERE o.tenantId = :t)")
                .setParameter("t", tenantId).executeUpdate()
            em.createQuery("DELETE FROM MetadataObject o WHERE o.tenantId = :t").setParameter("t", tenantId).executeUpdate()
            em.createQuery("DELETE FROM MetadataType t WHERE t.tenantId = :t").setParameter("t", tenantId).executeUpdate()
        }
    }

    /** SAP delivers meters and locations, the internal system sites and grid areas, the LoRaWAN server device assignments. */
    private fun loadSources() {
        nodes.save(tenantId, sapLocations, listOf(
            mapOf("MELO" to melo1, "MALO" to "51238696781"),
            mapOf("MELO" to melo2, "MALO" to "51238696792"),
        ), "sap")
        nodes.save(tenantId, sapMeters, listOf(
            mapOf("EQUNR" to "10001", "SERNR" to "1ESY1160000001", "HERST" to "ESY", "MELO" to melo1),
            mapOf("EQUNR" to "10002", "SERNR" to "1ESY1160000002", "HERST" to "ESY", "MELO" to melo2),
        ), "sap")
        nodes.save(tenantId, internalLocations, listOf(
            mapOf("malo" to "51238696781", "site" to "S-1"),
            mapOf("malo" to "51238696792", "site" to "S-2"),
        ), "masterdata")
        nodes.save(tenantId, sites, listOf(
            mapOf("site" to "S-1", "city" to "Leipzig", "zip" to "04109", "area" to "NB-17"),
            mapOf("site" to "S-2", "city" to "Leipzig", "zip" to "04229", "area" to "NB-17"),
        ), "masterdata")
        nodes.save(tenantId, lorawan, listOf(mapOf("devEUI" to "70B3D57ED0000001", "meter" to "1ESY1160000001")), "lns")
    }

    @Suppress("UNCHECKED_CAST")
    private fun enrich(record: Map<String, Any?>) = nodes.enrich(tenantId, enrichReading, record).output as Map<String, Any?>

    @Test
    fun readingIsEnrichedAcrossThreeSourceSystems() {
        loadSources()
        val out = enrich(mapOf("devEUI" to "70B3D57ED0000001", "energyKwh" to 1234.5, "ts" to t1.toString()))

        assertEquals(1234.5, out["energyKwh"])
        assertEquals("ESY", (out["meter"] as Map<*, *>)["manufacturer"])
        assertEquals("51238696781", ((out["malo"] as Map<*, *>)["ids"] as Map<*, *>)["malo"])
        assertEquals("04109", (out["location"] as Map<*, *>)["postalCode"])
        assertEquals("NB-17", ((out["gridArea"] as Map<*, *>)["ids"] as Map<*, *>)["grid_area_id"], out.toString())
    }

    @Test
    fun meterExchangeEnrichesEachReadingWithTheMeterValidAtItsTime() {
        loadSources()
        // The LoRaWAN device is moved to the second meter on t1.
        nodes.save(tenantId, lorawan, listOf(mapOf("devEUI" to "70B3D57ED0000001", "meter" to "1ESY1160000002", "from" to t1.toString())), "lns")

        val before = enrich(mapOf("devEUI" to "70B3D57ED0000001", "ts" to t1.minusSeconds(60).toString()))
        val after = enrich(mapOf("devEUI" to "70B3D57ED0000001", "ts" to t1.plusSeconds(60).toString()))
        assertEquals("1ESY1160000001", ((before["meter"] as Map<*, *>)["ids"] as Map<*, *>)["meter_number"])
        assertEquals("1ESY1160000002", ((after["meter"] as Map<*, *>)["ids"] as Map<*, *>)["meter_number"])
        assertEquals("04229", (after["location"] as Map<*, *>)["postalCode"])
    }

    @Test
    fun attributeChangesAreVersioned() {
        loadSources()
        val site = SaveMetadataConfig.parse(mapOf(
            "type" to "location", "match" to mapOf("scheme" to "location_id", "field" to "site"),
            "attributes" to mapOf("city" to "city"), "validFromField" to "from"))
        nodes.save(tenantId, site, listOf(mapOf("site" to "S-1", "city" to "Markkleeberg", "from" to t1.toString())), "masterdata")

        val id = metadata.resolve(tenantId, "location_id", "S-1")!!
        assertEquals("Leipzig", metadata.view(id, t0)!!.attributes["city"])
        assertEquals("Markkleeberg", metadata.view(id, t1)!!.attributes["city"])
        assertEquals("04109", metadata.view(id, t1)!!.attributes["postalCode"])      // merged, not replaced

        // Repeating a delivery changes nothing (exactly-once replays, re-imports).
        val again = nodes.save(tenantId, site, listOf(mapOf("site" to "S-1", "city" to "Markkleeberg", "from" to t1.toString())), "masterdata")
        assertEquals(1, again.unchanged)
    }

    @Test
    fun unknownDeviceIsKeptDroppedOrFailedAsConfigured() {
        loadSources()
        val reading = mapOf("devEUI" to "70B3D57ED00000FF", "energyKwh" to 1.0)
        assertEquals(reading, enrich(reading))
        val drop = EnrichConfig.parse(mapOf("match" to mapOf("scheme" to "lorawan.devEUI", "field" to "devEUI"), "onMissing" to "drop"))
        assertNull(nodes.enrich(tenantId, drop, reading).output)
        val fail = EnrichConfig.parse(mapOf("match" to mapOf("scheme" to "lorawan.devEUI", "field" to "devEUI"), "onMissing" to "fail"))
        assertThrows(IllegalStateException::class.java) { nodes.enrich(tenantId, fail, reading) }
    }

    @Test
    fun attributeValuesFollowTheSchemaTypes() {
        val meters = SaveMetadataConfig.parse(mapOf(
            "type" to "meter",
            "match" to mapOf("scheme" to "sap.equipment", "field" to "EQUNR"),
            "attributes" to mapOf("phases" to "PHASEN", "manufacturer" to "HERST"),
        ))
        nodes.save(tenantId, meters, mapOf("EQUNR" to "10000001", "PHASEN" to "3", "HERST" to "0815"), "test")
        val id = metadata.resolve(tenantId, "sap.equipment", "10000001", Instant.now())!!
        val attributes = metadata.view(id, Instant.now())!!.attributes
        assertEquals(3, (attributes["phases"] as Number).toInt())
        // Strings stay strings, leading zeros included.
        assertEquals("0815", attributes["manufacturer"])
        val e = assertThrows(MetadataValidationException::class.java) {
            nodes.save(tenantId, meters, mapOf("EQUNR" to "10000002", "PHASEN" to "drei"), "test")
        }
        assertTrue(e.message!!.contains("phases"), e.message)
    }

    @Test
    fun identifiersAreValidatedAgainstTheirScheme() {
        val badMalo = SaveMetadataConfig.parse(mapOf("type" to "market_location", "match" to mapOf("scheme" to "malo", "field" to "malo")))
        val e = assertThrows(MetadataValidationException::class.java) { nodes.save(tenantId, badMalo, mapOf("malo" to "12AB"), "test") }
        assertTrue(e.message!!.contains("MaLo-ID"), e.message)
    }

    @Test
    fun pipelinesSaveAndUseMetadata() {
        val (sapPipeline, readingPipeline) = QuarkusTransaction.requiringNew().call {
            val tenant = em.find(Tenant::class.java, tenantId)
            val sap = Pipeline().apply {
                this.tenant = tenant; name = "SAP meters"; isActive = true
                graphDefinition = """{"nodes":[{"id":"in","type":"IMPORT"},
                    {"id":"save","type":"EXPORT","saveMetadata":{"type":"meter","match":{"scheme":"meter_number","field":"SERNR"},
                     "attributes":{"manufacturer":"HERST"}}}],"edges":[{"source":"in","target":"save"}]}"""
            }.also { em.persist(it) }
            val readings = Pipeline().apply {
                this.tenant = tenant; name = "Readings"; isActive = true
                graphDefinition = """{"nodes":[{"id":"in","type":"IMPORT"},
                    {"id":"enrich","type":"PROCESSING","enrich":{"match":{"scheme":"meter_number","field":"meter"},"outputs":[{"as":"meterInfo"}]}},
                    {"id":"out","type":"EXPORT","saveMetadata":{"type":"meter","match":{"scheme":"meter_number","field":"meter"},"attributes":{"lastReading":"kwh"}}}],
                    "edges":[{"source":"in","target":"enrich"},{"source":"enrich","target":"out"}]}"""
            }.also { em.persist(it) }
            sap.id!! to readings.id!!
        }
        val sink = messaging.sink<String>("pipeline-commands")
        sink.clear()
        queue.enqueue(sapPipeline, TriggerType.DATA, mapOf("SERNR" to "1ESY1160000009", "HERST" to "ESY"))
        sink.received().forEach { assertTrue(consumer.consume(it.payload).success) }
        sink.clear()
        val runId = queue.enqueue(readingPipeline, TriggerType.DATA, mapOf("meter" to "1ESY1160000009", "kwh" to 42))
        sink.received().forEach { assertTrue(consumer.consume(it.payload).success) }

        metadata.invalidateAll()
        val meter = metadata.view(metadata.resolve(tenantId, "meter_number", "1ESY1160000009")!!)!!
        assertEquals("ESY", meter.attributes["manufacturer"])
        assertEquals(42, meter.attributes["lastReading"])
        QuarkusTransaction.requiringNew().run {
            assertEquals(PipelineRunStatus.SUCCESS, em.find(PipelineRun::class.java, runId).status)
            em.createQuery("DELETE FROM PipelineRunNodeLog l WHERE l.run.pipeline.id IN (:a, :b)").setParameter("a", sapPipeline).setParameter("b", readingPipeline).executeUpdate()
            em.createQuery("DELETE FROM PipelineRun r WHERE r.pipeline.id IN (:a, :b)").setParameter("a", sapPipeline).setParameter("b", readingPipeline).executeUpdate()
            em.createQuery("DELETE FROM Pipeline p WHERE p.id IN (:a, :b)").setParameter("a", sapPipeline).setParameter("b", readingPipeline).executeUpdate()
        }
    }

    @Test
    fun apiListsSearchesAndShowsHistory() {
        loadSources()
        val token = TestAuthHelpers.createAdminToken()
        given().header("Authorization", "Bearer $token")
            .get("/api/internal/v1/metadata/objects?tenantId=$tenantId&type=meter&q=0000002")
            .then().statusCode(200).body("total", equalTo(1)).body("items[0].identifiers.meter_number", equalTo("1ESY1160000002"))
        given().header("Authorization", "Bearer $token")
            .get("/api/internal/v1/metadata/resolve?tenantId=$tenantId&scheme=lorawan.devEUI&value=70B3D57ED0000001&path=meter,metering_location")
            .then().statusCode(200).body("[0].ids.melo", equalTo(melo1))
    }
}
