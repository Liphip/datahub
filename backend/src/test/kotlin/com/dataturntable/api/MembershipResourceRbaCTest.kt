package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
class MembershipResourceRbaCTest {

    @Test
    fun listMembershipsWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .`when`().get("/api/internal/v1/tenants/00000000-0000-0000-0000-000000000001/memberships")
            .then()
            .statusCode(401)
    }

    @Test
    fun createMembershipWithoutAuthReturnsUnauthorized() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("authUserId" to "00000000-0000-0000-0000-000000000002", "roleId" to "00000000-0000-0000-0000-000000000003"))
            .`when`().post("/api/internal/v1/tenants/00000000-0000-0000-0000-000000000001/memberships")
            .then()
            .statusCode(401)
    }
}
