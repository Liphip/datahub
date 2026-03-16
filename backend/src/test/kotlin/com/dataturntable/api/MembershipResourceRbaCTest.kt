package com.dataturntable.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
class MembershipResourceRbaCTest {

    @Test
    fun listMembershipsWithoutAuthReturnsForbidden() {
        RestAssured.given()
            .`when`().get("/tenants/00000000-0000-0000-0000-000000000001/memberships")
            .then()
            .statusCode(403)
    }

    @Test
    fun createMembershipWithoutAuthReturnsForbidden() {
        RestAssured.given()
            .contentType("application/json")
            .body(mapOf("authUserId" to "00000000-0000-0000-0000-000000000002", "roleId" to "00000000-0000-0000-0000-000000000003"))
            .`when`().post("/tenants/00000000-0000-0000-0000-000000000001/memberships")
            .then()
            .statusCode(403)
    }
}
