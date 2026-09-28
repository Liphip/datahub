package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.startsWith
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class ApiKeyResourceTest {

    private val token by lazy { TestAuthHelpers.createAdminToken() }
    private fun api() = given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)

    private fun tenant(): String {
        val root = api().get("/api/internal/v1/tenants").then().statusCode(200).extract().jsonPath().getString("[0].id")
        return api().body(mapOf("name" to "keys-${UUID.randomUUID()}", "parentId" to root))
            .post("/api/internal/v1/tenants").then().statusCode(201).extract().jsonPath().getString("id")
    }

    private fun pipeline(tenantId: String): String {
        api().post("/api/internal/v1/metadata/templates/iot/install?tenantId=$tenantId").then().statusCode(200)
        val id = api().body(mapOf("name" to "Readings ${UUID.randomUUID()}", "graphDefinition" to mapOf(
            "nodes" to listOf(mapOf("id" to "in", "type" to "IMPORT"), mapOf("id" to "save", "type" to "EXPORT",
                "saveMetadata" to mapOf("type" to "device", "match" to mapOf("scheme" to "device_id", "field" to "devEUI")))),
            "edges" to listOf(mapOf("source" to "in", "target" to "save")))))
            .post("/api/internal/v1/pipelines?tenantId=$tenantId").then().log().ifValidationFails().statusCode(201).extract().jsonPath().getString("id")
        api().body(mapOf("isActive" to true)).patch("/api/internal/v1/pipelines/$id").then().statusCode(200)
        return id
    }

    private fun ingest(pipelineId: String, key: String?) = given().contentType(ContentType.JSON)
        .apply { if (key != null) header("X-API-Key", key) }
        .body(mapOf("devEUI" to "70B3D57ED0000001", "energyKwh" to 1.5))
        .post("/api/ingest/v1/ingest/$pipelineId")

    @Test
    fun `a key is shown once, grants exactly its pipelines and stops working when revoked`() {
        val tenantId = tenant()
        val granted = pipeline(tenantId)
        val other = pipeline(tenantId)

        val created = api().body(mapOf("name" to "LoRaWAN gateway", "pipelineIds" to listOf(granted)))
            .post("/api/internal/v1/api-keys?tenantId=$tenantId")
            .then().statusCode(201).body("key", startsWith("dt_")).body("pipelineIds[0]", equalTo(granted))
            .extract().jsonPath()
        val key = created.getString("key")

        api().get("/api/internal/v1/api-keys?tenantId=$tenantId")
            .then().statusCode(200).body("[0].name", equalTo("LoRaWAN gateway")).body("[0].key", equalTo(null))

        ingest(granted, key).then().statusCode(202)
        ingest(other, key).then().statusCode(401)
        ingest(granted, null).then().statusCode(401)
        ingest(granted, "dt_wrong").then().statusCode(401)

        api().delete("/api/internal/v1/api-keys/${created.getString("id")}").then().statusCode(204)
        ingest(granted, key).then().statusCode(401)
    }

    @Test
    fun `keys are validated against the tenant`() {
        val tenantId = tenant()
        val foreign = pipeline(tenant())
        api().body(mapOf("name" to "x", "pipelineIds" to listOf(foreign)))
            .post("/api/internal/v1/api-keys?tenantId=$tenantId").then().statusCode(400)
        api().body(mapOf("name" to "x")).post("/api/internal/v1/api-keys?tenantId=$tenantId").then().statusCode(400)
        given().get("/api/internal/v1/api-keys?tenantId=$tenantId").then().statusCode(401)
    }
}
