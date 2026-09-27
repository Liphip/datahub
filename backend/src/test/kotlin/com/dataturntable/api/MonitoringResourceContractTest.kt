package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

@QuarkusTest
class MonitoringResourceContractTest {
    @Test
    fun `public monitoring summary is aggregate-only while detailed logs require authentication`() {
        given().`when`().get("/api/v1/monitoring/summary").then().statusCode(200)
        given().`when`().get("/api/v1/monitoring/recent-logs").then().statusCode(401)
        given().`when`().get("/api/v1/monitoring/pipeline-stats").then().statusCode(401)
    }
}
