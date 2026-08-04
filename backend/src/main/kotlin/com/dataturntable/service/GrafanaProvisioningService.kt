package com.dataturntable.service

import com.dataturntable.grafana.GrafanaInstanceConfig
import com.dataturntable.grafana.GrafanaSyncScopeType
import com.dataturntable.grafana.GrafanaSyncState
import com.dataturntable.grafana.GrafanaSyncStatus
import com.dataturntable.grafana.GrafanaTargetInstance
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@ApplicationScoped
/**
 * Defines the class `GrafanaProvisioningService`, the contract implemented by this source unit.
 */
class GrafanaProvisioningService {

    private val log = Logger.getLogger(GrafanaProvisioningService::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val secureRandom = SecureRandom()

    @Inject
    lateinit var grafanaInstanceConfig: GrafanaInstanceConfig

    @PersistenceContext
    lateinit var em: EntityManager

    /**
     * Defines the class `ApplySummary`, the contract implemented by this source unit.
     */
    data class ApplySummary(
        val processed: Int,
        val synced: Int,
        val failed: Int,
        val skipped: Int,
    )

    @Transactional
    /**
     * Implements `applyPending` for this source unit.
     */
    fun applyPending(limit: Int = 50, target: GrafanaTargetInstance? = null): ApplySummary {
        val conditions = mutableListOf("g.syncStatus = :status")
        if (target != null) conditions += "g.targetInstance = :target"
        val jpql = buildString {
            append("SELECT g FROM GrafanaSyncState g WHERE ")
            append(conditions.joinToString(" AND "))
            append(" ORDER BY g.updatedAt ASC")
        }
        val query = em.createQuery(jpql, GrafanaSyncState::class.java)
            .setParameter("status", GrafanaSyncStatus.PENDING)
            .setMaxResults(limit.coerceIn(1, 500))
        if (target != null) query.setParameter("target", target)

        var synced = 0
        var failed = 0
        var skipped = 0

        query.resultList.forEach { state ->
            val config = grafanaInstanceConfig.forTarget(state.targetInstance)
            if (!config.isConfigured()) {
                skipped += 1
                return@forEach
            }
            try {
                applyState(state, config)
                state.syncStatus = GrafanaSyncStatus.SYNCED
                state.lastSyncedAt = Instant.now()
                state.lastError = null
                state.updatedAt = Instant.now()
                em.merge(state)
                synced += 1
            } catch (e: Exception) {
                log.errorf("Grafana sync failed for state %s: %s", state.id, e.message)
                state.syncStatus = GrafanaSyncStatus.FAILED
                state.lastError = e.message?.take(4000)
                state.updatedAt = Instant.now()
                em.merge(state)
                failed += 1
            }
        }

        return ApplySummary(
            processed = synced + failed + skipped,
            synced = synced,
            failed = failed,
            skipped = skipped
        )
    }

    /**
     * Implements `applyState` for this source unit.
     */
    private fun applyState(state: GrafanaSyncState, config: GrafanaInstanceConfig.Target) {
        val desired = objectMapper.readTree(state.desiredState ?: "{}")
        when (state.scopeType) {
            GrafanaSyncScopeType.TENANT -> applyTenantState(state, desired, config)
            GrafanaSyncScopeType.USER -> applyUserState(state, desired, config)
            GrafanaSyncScopeType.MEMBERSHIP -> applyMembershipState(state, desired, config)
        }
    }

    /**
     * Implements `applyTenantState` for this source unit.
     */
    private fun applyTenantState(state: GrafanaSyncState, desired: JsonNode, config: GrafanaInstanceConfig.Target) {
        if (state.targetInstance != GrafanaTargetInstance.DASHBOARD) return
        if (desired.path("deleted").asBoolean(false)) {
            val orgName = desired.path("dashboardGrafana").path("organizationName").asText(null)
                ?: desired.path("name").asText(null)
            if (!orgName.isNullOrBlank()) {
                val orgId = findOrgIdByName(config, orgName)
                if (orgId != null) {
                    deleteOrg(config, orgId)
                }
            }
            return
        }
        val orgName = desired.path("dashboardGrafana").path("organizationName").asText(null)
            ?: desired.path("name").asText(null)
            ?: throw IllegalStateException("Missing organization name for tenant sync")
        val orgId = findOrgIdByName(config, orgName) ?: createOrg(config, orgName)
        if (orgId <= 0) throw IllegalStateException("Failed to resolve Grafana organization for $orgName")
        ensureTenantDatasource(config, orgId, desired)
    }

    /**
     * Implements `applyUserState` for this source unit.
     */
    private fun applyUserState(state: GrafanaSyncState, desired: JsonNode, config: GrafanaInstanceConfig.Target) {
        val email = desired.path("email").asText("")
        if (email.isBlank()) return
        if (desired.path("deleted").asBoolean(false)) {
            findUserByEmail(config, email)?.path("id")?.asInt()?.takeIf { it > 0 }?.let { deleteUser(config, it) }
            return
        }

        if (state.targetInstance == GrafanaTargetInstance.MONITORING && !desired.path("monitoringGrafanaAccess").asBoolean(false)) {
            findUserByEmail(config, email)?.path("id")?.asInt()?.takeIf { it > 0 }?.let { deleteUser(config, it) }
            return
        }

        val user = ensureGrafanaUser(config, desired)
        val userId = user.path("id").asInt()
        if (userId <= 0) {
            throw IllegalStateException("Failed to resolve Grafana user for $email")
        }

        if (state.targetInstance == GrafanaTargetInstance.DASHBOARD) {
            ensureGrafanaAdmin(config, userId, desired.path("dashboardGrafanaAdmin").asBoolean(false))
        }
        if (state.targetInstance == GrafanaTargetInstance.MONITORING) {
            ensureGrafanaAdmin(config, userId, desired.path("monitoringGrafanaAdmin").asBoolean(false))
        }
    }

    /**
     * Implements `applyMembershipState` for this source unit.
     */
    private fun applyMembershipState(state: GrafanaSyncState, desired: JsonNode, config: GrafanaInstanceConfig.Target) {
        if (state.targetInstance != GrafanaTargetInstance.DASHBOARD) return
        val email = desired.path("userEmail").asText("")
        val tenantName = desired.path("tenantName").asText("")
        if (email.isBlank() || tenantName.isBlank()) return
        if (desired.path("deleted").asBoolean(false)) {
            val user = findUserByEmail(config, email) ?: return
            val orgId = findOrgIdByName(config, tenantName) ?: return
            removeUserFromOrg(config, orgId, user.path("id").asInt())
            return
        }

        val user = ensureGrafanaUser(config, desired.deepCopy<JsonNode>().apply {
            if (!hasNonNull("email")) {
                (this as? com.fasterxml.jackson.databind.node.ObjectNode)?.put("email", email)
            }
        })
        val orgId = findOrgIdByName(config, tenantName) ?: createOrg(config, tenantName)
        val role = desired.path("grafanaRole").asText("Viewer")
        ensureUserInOrg(config, orgId, user.path("id").asInt(), role)
    }

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun findOrgIdByName(config: GrafanaInstanceConfig.Target, orgName: String): Int? {
        val encodedName = URLEncoder.encode(orgName, StandardCharsets.UTF_8)
        val response = send(config, "GET", "/api/orgs/name/$encodedName", null)
        return when (response.statusCode()) {
            200 -> objectMapper.readTree(response.body()).path("id").asInt()
            404 -> null
            else -> throw IllegalStateException("Failed to lookup Grafana org $orgName: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Creates or persists the requested state.
     */
    private fun createOrg(config: GrafanaInstanceConfig.Target, orgName: String): Int {
        val payload = mapOf("name" to orgName)
        val response = send(config, "POST", "/api/orgs", payload)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Failed to create Grafana org $orgName: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
        return objectMapper.readTree(response.body()).path("orgId").asInt()
    }

    /**
     * Implements `ensureTenantDatasource` for this source unit.
     */
    private fun ensureTenantDatasource(config: GrafanaInstanceConfig.Target, orgId: Int, desired: JsonNode) {
        val datasourceConfig = config.datasource ?: return
        val datasourceName = desired.path("dashboardGrafana").path("datasourceName").asText("").ifBlank {
            "tenant-${desired.path("tenantId").asText("unknown")}-data"
        }
        val schema = desired.path("pipelineSchema").asText("").ifBlank {
            throw IllegalStateException("Missing pipeline schema for tenant datasource")
        }

        val payload = mapOf(
            "name" to datasourceName,
            "type" to "postgres",
            "access" to "proxy",
            "url" to "${datasourceConfig.host}:${datasourceConfig.port}",
            "user" to datasourceConfig.user,
            "database" to datasourceConfig.database,
            "isDefault" to true,
            "editable" to false,
            "jsonData" to mapOf(
                "sslmode" to datasourceConfig.sslMode,
                "postgresVersion" to 1500,
                "timescaledb" to true,
                "database" to datasourceConfig.database,
                "searchPath" to schema
            ),
            "secureJsonData" to mapOf(
                "password" to datasourceConfig.password
            )
        )

        val existing = findDatasourceByName(config, orgId, datasourceName)
        if (existing == null) {
            val createResponse = send(config, "POST", "/api/datasources", payload, mapOf("X-Grafana-Org-Id" to orgId.toString()))
            if (createResponse.statusCode() !in 200..299) {
                throw IllegalStateException("Failed to create Grafana datasource $datasourceName for org $orgId: HTTP ${createResponse.statusCode()} ${createResponse.body().take(300)}")
            }
            return
        }

        val datasourceId = existing.path("id").asInt()
        val updateResponse = send(config, "PUT", "/api/datasources/$datasourceId", payload, mapOf("X-Grafana-Org-Id" to orgId.toString()))
        if (updateResponse.statusCode() !in 200..299) {
            throw IllegalStateException("Failed to update Grafana datasource $datasourceName for org $orgId: HTTP ${updateResponse.statusCode()} ${updateResponse.body().take(300)}")
        }
    }

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun findDatasourceByName(config: GrafanaInstanceConfig.Target, orgId: Int, datasourceName: String): JsonNode? {
        val encodedName = URLEncoder.encode(datasourceName, StandardCharsets.UTF_8)
        val response = send(config, "GET", "/api/datasources/name/$encodedName", null, mapOf("X-Grafana-Org-Id" to orgId.toString()))
        return when (response.statusCode()) {
            200 -> objectMapper.readTree(response.body())
            404 -> null
            else -> throw IllegalStateException("Failed to lookup Grafana datasource $datasourceName in org $orgId: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun findUserByEmail(config: GrafanaInstanceConfig.Target, email: String): JsonNode? {
        val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8)
        val response = send(config, "GET", "/api/users/lookup?loginOrEmail=$encodedEmail", null)
        return when (response.statusCode()) {
            200 -> objectMapper.readTree(response.body())
            404 -> null
            else -> throw IllegalStateException("Failed to lookup Grafana user $email: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Implements `ensureGrafanaUser` for this source unit.
     */
    private fun ensureGrafanaUser(config: GrafanaInstanceConfig.Target, desired: JsonNode): JsonNode {
        val email = desired.path("email").asText("").trim()
        if (email.isBlank()) throw IllegalStateException("Missing user email for Grafana sync")

        findUserByEmail(config, email)?.let { return it }

        val createPayload = mapOf(
            "name" to buildDisplayName(desired, email),
            "email" to email,
            "login" to email,
            "password" to generateProvisioningPassword()
        )
        val createResponse = send(config, "POST", "/api/admin/users", createPayload)
        if (createResponse.statusCode() !in 200..299 && createResponse.statusCode() != 409) {
            throw IllegalStateException(
                "Failed to create Grafana user $email: HTTP ${createResponse.statusCode()} ${createResponse.body().take(300)}"
            )
        }
        return findUserByEmail(config, email)
            ?: throw IllegalStateException("Grafana user $email was not found after provisioning")
    }

    /**
     * Implements `ensureGrafanaAdmin` for this source unit.
     */
    private fun ensureGrafanaAdmin(config: GrafanaInstanceConfig.Target, userId: Int, isAdmin: Boolean) {
        val payload = mapOf("isGrafanaAdmin" to isAdmin)
        val response = send(config, "PUT", "/api/admin/users/$userId/permissions", payload)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Failed to update Grafana admin permissions for user $userId: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Implements `ensureUserInOrg` for this source unit.
     */
    private fun ensureUserInOrg(config: GrafanaInstanceConfig.Target, orgId: Int, userId: Int, role: String) {
        val payload = mapOf("userId" to userId, "role" to role)
        val addResponse = send(config, "POST", "/api/orgs/$orgId/users", payload)
        when (addResponse.statusCode()) {
            in 200..299 -> return
            409 -> updateUserOrgRole(config, orgId, userId, role)
            else -> {
                val body = addResponse.body().lowercase()
                if ("already added" in body || "user is already added" in body || "member already exists" in body) {
                    updateUserOrgRole(config, orgId, userId, role)
                } else {
                    throw IllegalStateException("Failed to add user $userId to org $orgId: HTTP ${addResponse.statusCode()} ${addResponse.body().take(300)}")
                }
            }
        }
    }

    /**
     * Removes, stops, or releases the requested state.
     */
    private fun removeUserFromOrg(config: GrafanaInstanceConfig.Target, orgId: Int, userId: Int) {
        val response = send(config, "DELETE", "/api/orgs/$orgId/users/$userId", null)
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw IllegalStateException("Failed to remove user $userId from org $orgId: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Removes, stops, or releases the requested state.
     */
    private fun deleteOrg(config: GrafanaInstanceConfig.Target, orgId: Int) {
        val response = send(config, "DELETE", "/api/orgs/$orgId", null)
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw IllegalStateException("Failed to delete Grafana org $orgId: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Removes, stops, or releases the requested state.
     */
    private fun deleteUser(config: GrafanaInstanceConfig.Target, userId: Int) {
        val response = send(config, "DELETE", "/api/admin/users/$userId", null)
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw IllegalStateException("Failed to delete Grafana user $userId: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Updates the requested state.
     */
    private fun updateUserOrgRole(config: GrafanaInstanceConfig.Target, orgId: Int, userId: Int, role: String) {
        val payload = mapOf("role" to role)
        val response = send(config, "PATCH", "/api/orgs/$orgId/users/$userId", payload)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Failed to update Grafana org role for user $userId in org $orgId: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
    }

    /**
     * Delivers or routes the named data to its configured destination.
     */
    private fun send(
        config: GrafanaInstanceConfig.Target,
        method: String,
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Authorization", basicAuth(config.adminUser, config.adminPassword))
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val payload = if (body != null) objectMapper.writeValueAsString(body) else null
        when (method.uppercase()) {
            "GET" -> requestBuilder.GET()
            "POST" -> requestBuilder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload ?: "{}"))
            "PUT" -> requestBuilder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(payload ?: "{}"))
            "PATCH" -> requestBuilder.header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(payload ?: "{}"))
            "DELETE" -> requestBuilder.DELETE()
            else -> throw IllegalArgumentException("Unsupported method $method")
        }
        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Implements `buildDisplayName` for this source unit.
     */
    private fun buildDisplayName(desired: JsonNode, email: String): String {
        val firstName = desired.path("firstName").asText("").trim()
        val lastName = desired.path("lastName").asText("").trim()
        val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        return fullName.ifBlank { email.substringBefore('@').ifBlank { email } }
    }

    /**
     * Implements `generateProvisioningPassword` for this source unit.
     */
    private fun generateProvisioningPassword(): String {
        val randomBytes = ByteArray(24)
        secureRandom.nextBytes(randomBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        return "Dt!${token}9a"
    }

    /**
     * Implements `basicAuth` for this source unit.
     */
    private fun basicAuth(user: String, password: String): String {
        val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }
}
