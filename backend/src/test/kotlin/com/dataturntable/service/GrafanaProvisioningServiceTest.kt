package com.dataturntable.service

import com.dataturntable.grafana.GrafanaInstanceConfig
import com.dataturntable.security.SensitiveDataRedactor
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class GrafanaProvisioningServiceTest {

    private val objectMapper = ObjectMapper()
    private var server: HttpServer? = null

    @AfterEach
    fun shutdownServer() {
        server?.stop(0)
    }

    @Test
    fun testEnsureGrafanaUserCreatesMissingUser() {
        val createdPayloads = mutableListOf<Map<*, *>>()
        val usersByEmail = ConcurrentHashMap<String, Map<String, Any>>()

        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/users/lookup", JsonHandler { exchange ->
                val email = exchange.requestURI.rawQuery.substringAfter("loginOrEmail=", "")
                    .replace("%40", "@")
                val user = usersByEmail[email]
                if (user == null) {
                    respond(exchange, 404, mapOf("message" to "user not found"))
                } else {
                    respond(exchange, 200, user)
                }
            })
            createContext("/api/admin/users", JsonHandler { exchange ->
                val payload = objectMapper.readValue(exchange.requestBody.readAllBytes(), Map::class.java)
                createdPayloads += payload
                val email = payload["email"].toString()
                usersByEmail[email] = mapOf("id" to 101, "email" to email, "login" to email)
                respond(exchange, 200, mapOf("id" to 101, "message" to "User created"))
            })
            start()
        }

        val service = GrafanaProvisioningService().apply { redactor = SensitiveDataRedactor() }
        val method = GrafanaProvisioningService::class.java.getDeclaredMethod(
            "ensureGrafanaUser",
            GrafanaInstanceConfig.Target::class.java,
            com.fasterxml.jackson.databind.JsonNode::class.java
        )
        method.isAccessible = true

        val result = method.invoke(
            service,
            GrafanaInstanceConfig.Target(
                enabled = true,
                baseUrl = "http://localhost:${server!!.address.port}",
                adminUser = "admin",
                adminPassword = "admin"
            ),
            objectMapper.readTree(
                """
                {
                  "email": "new.user@example.com",
                  "firstName": "New",
                  "lastName": "User"
                }
                """.trimIndent()
            )
        ) as com.fasterxml.jackson.databind.JsonNode

        assertEquals(101, result.path("id").asInt())
        assertEquals(1, createdPayloads.size)
        assertEquals("new.user@example.com", createdPayloads.first()["email"])
        assertEquals("new.user@example.com", createdPayloads.first()["login"])
        assertEquals("New User", createdPayloads.first()["name"])
        assertTrue((createdPayloads.first()["password"] as String).startsWith("Dt!"))
    }

    @Test
    fun `a failed user-creation response redacts an echoed provisioning password before it reaches the thrown message`() {
        // Regression test for a real gap found this session: Grafana's error response for a
        // rejected /api/admin/users POST could echo submitted request fields back (common for
        // validation 400s), and the submitted payload includes a freshly generated provisioning
        // password. That message previously flowed unredacted into a log line and the persisted
        // GrafanaSyncState.lastError column, which GrafanaSyncResource exposes via its JSON API.
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/users/lookup", JsonHandler { exchange -> respond(exchange, 404, mapOf("message" to "user not found")) })
            createContext("/api/admin/users", JsonHandler { exchange ->
                val payload = objectMapper.readValue(exchange.requestBody.readAllBytes(), Map::class.java)
                // Simulate Grafana echoing the submitted (invalid) password back in a validation error.
                respond(exchange, 400, mapOf("message" to "invalid payload: password=${payload["password"]}"))
            })
            start()
        }

        val service = GrafanaProvisioningService().apply { redactor = SensitiveDataRedactor() }
        val method = GrafanaProvisioningService::class.java.getDeclaredMethod(
            "ensureGrafanaUser",
            GrafanaInstanceConfig.Target::class.java,
            com.fasterxml.jackson.databind.JsonNode::class.java
        )
        method.isAccessible = true

        val thrown = try {
            method.invoke(
                service,
                GrafanaInstanceConfig.Target(
                    enabled = true,
                    baseUrl = "http://localhost:${server!!.address.port}",
                    adminUser = "admin",
                    adminPassword = "admin"
                ),
                objectMapper.readTree("""{"email":"leak-check@example.com"}""")
            )
            null
        } catch (e: java.lang.reflect.InvocationTargetException) {
            e.cause
        }

        assertTrue(thrown is IllegalStateException, "expected the HTTP 400 to surface as an IllegalStateException")
        val message = thrown!!.message!!
        assertFalse(message.contains("Dt!"), "the generated provisioning password prefix must not appear in the thrown message: $message")
        assertTrue(message.contains("[REDACTED]"))
    }

    @Test
    fun testEnsureGrafanaUserReusesExistingUser() {
        var createCalls = 0

        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/users/lookup", JsonHandler { exchange ->
                respond(exchange, 200, mapOf("id" to 7, "email" to "existing@example.com", "login" to "existing@example.com"))
            })
            createContext("/api/admin/users", JsonHandler { exchange ->
                createCalls += 1
                respond(exchange, 500, mapOf("error" to "should not be called"))
            })
            start()
        }

        val service = GrafanaProvisioningService()
        val method = GrafanaProvisioningService::class.java.getDeclaredMethod(
            "ensureGrafanaUser",
            GrafanaInstanceConfig.Target::class.java,
            com.fasterxml.jackson.databind.JsonNode::class.java
        )
        method.isAccessible = true

        val result = method.invoke(
            service,
            GrafanaInstanceConfig.Target(
                enabled = true,
                baseUrl = "http://localhost:${server!!.address.port}",
                adminUser = "admin",
                adminPassword = "admin"
            ),
            objectMapper.readTree("""{"email":"existing@example.com"}""")
        ) as com.fasterxml.jackson.databind.JsonNode

        assertEquals(7, result.path("id").asInt())
        assertEquals(0, createCalls)
    }

    private fun respond(exchange: HttpExchange, status: Int, payload: Map<String, Any>) {
        val bytes = objectMapper.writeValueAsBytes(payload)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output: OutputStream ->
            output.write(bytes)
        }
    }

    private class JsonHandler(private val handler: (HttpExchange) -> Unit) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            handler(exchange)
        }
    }
}
