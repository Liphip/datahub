package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
class ConnectorResourceUnitTest {

    @Test
    fun testListWithoutTenantIdReturns400() {
        RestAssured.given()
            .`when`().get("/connectors")
            .then()
            .statusCode(400)
    }
}
