package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
// BUG: Test omits the connector API base path
// Details: The request to /connectors never reaches ConnectorResource, whose route begins /api/internal/v1/connectors. Use the actual route before asserting its tenant-id validation behavior.
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
