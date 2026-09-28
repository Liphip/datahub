package com.dataturntable.api

import com.dataturntable.events.ConsumerDeadLetterService
import com.dataturntable.test.TestAuthHelpers
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Test

@QuarkusTest
class EventDlqResourceTest {

    @Inject
    lateinit var deadLetters: ConsumerDeadLetterService

    @Test
    fun `platform administrator can inspect redacted dead letters`() {
        deadLetters.record("pipeline-input", """{"runId":"x","password":"hunter2"}""", "Pipeline not found")
        given().header("Authorization", "Bearer ${TestAuthHelpers.createAdminToken()}")
            .`when`().get("/api/internal/v1/events/dlq/consumer")
            .then().statusCode(200)
            .body("error", hasItem("Pipeline not found"))
            .body("payload", hasItem(containsString("[REDACTED]")))
            .body("payload", not(hasItem(containsString("hunter2"))))
    }

    @Test
    fun `dead letters require authentication`() {
        given().`when`().get("/api/internal/v1/events/dlq").then().statusCode(401)
    }
}
