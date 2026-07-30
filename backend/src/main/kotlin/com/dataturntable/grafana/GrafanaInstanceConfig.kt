package com.dataturntable.grafana

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

@ApplicationScoped
class GrafanaInstanceConfig(
    @ConfigProperty(name = "dataturntable.grafana.dashboard.enabled", defaultValue = "false")
    private val dashboardEnabled: Boolean,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.base-url", defaultValue = "")
    private val dashboardBaseUrl: String,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.admin-user", defaultValue = "")
    private val dashboardAdminUser: String,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.admin-password", defaultValue = "")
    private val dashboardAdminPassword: String,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.datasource.host", defaultValue = "")
    private val dashboardDatasourceHost: Optional<String>,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.datasource.port", defaultValue = "5432")
    private val dashboardDatasourcePort: Int,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.datasource.database", defaultValue = "")
    private val dashboardDatasourceDatabase: Optional<String>,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.datasource.user", defaultValue = "")
    private val dashboardDatasourceUser: Optional<String>,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.datasource.password", defaultValue = "")
    private val dashboardDatasourcePassword: Optional<String>,
    @ConfigProperty(name = "dataturntable.grafana.dashboard.datasource.ssl-mode", defaultValue = "disable")
    private val dashboardDatasourceSslMode: String,
    @ConfigProperty(name = "dataturntable.grafana.monitoring.enabled", defaultValue = "false")
    private val monitoringEnabled: Boolean,
    @ConfigProperty(name = "dataturntable.grafana.monitoring.base-url", defaultValue = "")
    private val monitoringBaseUrl: String,
    @ConfigProperty(name = "dataturntable.grafana.monitoring.admin-user", defaultValue = "")
    private val monitoringAdminUser: String,
    @ConfigProperty(name = "dataturntable.grafana.monitoring.admin-password", defaultValue = "")
    private val monitoringAdminPassword: String,
) {

    data class Target(
        val enabled: Boolean,
        val baseUrl: String,
        val adminUser: String,
        val adminPassword: String,
        val datasource: DatasourceTarget? = null,
    ) {
        fun isConfigured(): Boolean =
            enabled && baseUrl.isNotBlank() && adminUser.isNotBlank() && adminPassword.isNotBlank()
    }

    data class DatasourceTarget(
        val host: String,
        val port: Int,
        val database: String,
        val user: String,
        val password: String,
        val sslMode: String,
    ) {
        fun isConfigured(): Boolean =
            host.isNotBlank() && port > 0 && database.isNotBlank() && user.isNotBlank() && password.isNotBlank()
    }

    fun forTarget(target: GrafanaTargetInstance): Target =
        when (target) {
            GrafanaTargetInstance.DASHBOARD -> Target(
                dashboardEnabled,
                dashboardBaseUrl.trimEnd('/'),
                dashboardAdminUser,
                dashboardAdminPassword,
                DatasourceTarget(
                    dashboardDatasourceHost.orElse(""),
                    dashboardDatasourcePort,
                    dashboardDatasourceDatabase.orElse(""),
                    dashboardDatasourceUser.orElse(""),
                    dashboardDatasourcePassword.orElse(""),
                    dashboardDatasourceSslMode
                ).takeIf { it.isConfigured() }
            )
            GrafanaTargetInstance.MONITORING -> Target(
                monitoringEnabled,
                monitoringBaseUrl.trimEnd('/'),
                monitoringAdminUser,
                monitoringAdminPassword,
                null
            )
        }
}
