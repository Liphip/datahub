package com.dataturntable.test

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Boots the application against a real PostgreSQL database (via Quarkus Dev Services,
 * which provisions an ephemeral Testcontainers-managed instance) with schema created by the
 * production Flyway migrations, instead of the default test profile's H2 + Hibernate
 * `drop-and-create` schema. Closes the NFR-014 gap: the default suite never actually executes
 * the Flyway migrations that define the real production schema, so a broken migration (bad SQL,
 * wrong column type, missing dependency ordering) would not be caught by `mvn test` alone.
 *
 * Uses the PostgreSQL database named by `DATATURNTABLE_IT_DB_URL` (with `DATATURNTABLE_IT_DB_USER`
 * and `DATATURNTABLE_IT_DB_PASSWORD`; the database is emptied first) or, without it, a Dev
 * Services container, which needs a Docker daemon. Tests are skipped when neither is available.
 */
class PostgresTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "postgres-it"

    // The profile supplies db-kind but deliberately no JDBC URL, so Dev Services provisions a
    // real PostgreSQL container. Deployment connection settings are scoped to `%prod` in the
    // main configuration and cannot shadow the Dev-Services URL during Flyway startup.
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.hibernate-orm.database.generation" to "validate",
        "quarkus.flyway.migrate-at-start" to "true",
        "quarkus.flyway.baseline-on-migrate" to "false",
        // Only the database is real; messaging stays in memory as in the default test profile.
        "quarkus.kafka.devservices.enabled" to "false",
        "mp.messaging.outgoing.pipeline-commands.connector" to "smallrye-in-memory",
        "mp.messaging.incoming.pipeline-input.enabled" to "false",
        "dataturntable.runtime.background-enabled" to "false",
    ) + (System.getenv("DATATURNTABLE_IT_DB_URL")?.let { url ->
        mapOf(
            "quarkus.datasource.devservices.enabled" to "false",
            "quarkus.datasource.jdbc.url" to url,
            "quarkus.datasource.username" to (System.getenv("DATATURNTABLE_IT_DB_USER") ?: "dataturntable"),
            "quarkus.datasource.password" to (System.getenv("DATATURNTABLE_IT_DB_PASSWORD") ?: ""),
            "quarkus.flyway.clean-disabled" to "false",
            "quarkus.flyway.clean-at-start" to "true",
        )
    } ?: if (available()) emptyMap() else mapOf(
        // Quarkus builds every test profile during discovery, also for skipped tests: without a
        // database and Docker, give it a placeholder so the (skipped) profile still builds.
        "quarkus.datasource.devservices.enabled" to "false",
        "quarkus.datasource.jdbc.url" to "jdbc:postgresql://localhost:1/unavailable",
        "quarkus.flyway.migrate-at-start" to "false",
        "quarkus.hibernate-orm.database.generation" to "none",
    ))

    companion object {
        /** A configured database or a Docker daemon for Dev Services. */
        @JvmStatic
        fun available(): Boolean = System.getenv("DATATURNTABLE_IT_DB_URL") != null ||
            java.io.File("/var/run/docker.sock").exists() || System.getenv("DOCKER_HOST") != null
    }
}
