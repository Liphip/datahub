package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
class ConnectorResourceUnitTest {

    @Test
    fun testListWithoutTenantIdFallsBackToTokenTenant() {
        RestAssured.given()
            .header("Authorization", "Bearer ${TestAuthHelpers.createAdminToken()}")
            .`when`().get("/api/internal/v1/connectors")
            .then()
            .statusCode(200)
    }
}
