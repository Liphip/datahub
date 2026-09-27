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
 * Requires a running Docker (or compatible) daemon; opt in with `-Dquarkus.test.profile=postgres-it`
 * or by tagging a test class with `@TestProfile(PostgresTestProfile::class)`.
 */
class PostgresTestProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "postgres-it"

    // The profile supplies db-kind but deliberately no JDBC URL, so Dev Services provisions a
    // real PostgreSQL container. Deployment connection settings are scoped to `%prod` in the
    // main configuration and cannot shadow the Dev-Services URL during Flyway startup.
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.hibernate-orm.database.generation" to "validate",
        "quarkus.flyway.migrate-at-start" to "true",
        "quarkus.flyway.baseline-on-migrate" to "false"
    )
}
