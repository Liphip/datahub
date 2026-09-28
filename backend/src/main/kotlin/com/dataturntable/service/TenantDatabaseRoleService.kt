package com.dataturntable.service

import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NFR-S8 / TEN-03: tenant isolation of the pipeline-data (hot tier) tables enforced by the
 * database itself, not only by the application computing the right schema name for a query.
 * Each tenant gets its own PostgreSQL role that owns its `tenant_<uuid>` schema; every pipeline
 * data operation runs under that role (`SET ROLE`) for the duration of the connection, so a
 * module config that names another tenant's schema — whether by a bug or by a malicious module —
 * is refused by PostgreSQL's own grants, independent of what the application's own schema
 * resolution decided.
 *
 * Requires the connecting database user to hold `CREATEROLE` (documented in the configuration
 * reference). Without it, provisioning fails once per tenant with a clear log line, and the
 * caller falls back to the shared connection's own privileges: isolation then rests on the
 * application-level schema check alone, exactly the gap NFR-S8 exists to close. That fallback is
 * deliberate and logged, never silent.
 */
@ApplicationScoped
class TenantDatabaseRoleService {
    private val log = Logger.getLogger(TenantDatabaseRoleService::class.java)

    /** Tenants whose role+schema ownership is confirmed provisioned on this connection pool's database. */
    private val provisioned = ConcurrentHashMap.newKeySet<UUID>()
    private val warnedOnce = ConcurrentHashMap.newKeySet<UUID>()

    fun roleName(tenantId: UUID): String = "tenant_${tenantId.toString().replace("-", "_")}"

    /**
     * Idempotent. Creates the tenant's role if missing, makes the connecting user a member of it
     * (needed to `SET ROLE` and to reassign schema ownership), and ensures [schema] is owned by
     * and fully granted to that role — including a schema that already existed under the old
     * shared-role model, and tables/sequences a prior run already created in it.
     */
    fun ensureRoleAndSchema(connection: Connection, tenantId: UUID, schema: String) {
        if (provisioned.contains(tenantId)) return
        val role = roleName(tenantId)
        try {
            connection.createStatement().use { stmt ->
                stmt.execute(
                    "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$role') " +
                        "THEN CREATE ROLE \"$role\" NOLOGIN; END IF; END \$\$;"
                )
                stmt.execute("GRANT \"$role\" TO CURRENT_USER")
                stmt.execute("CREATE SCHEMA IF NOT EXISTS \"$schema\"")
                stmt.execute("ALTER SCHEMA \"$schema\" OWNER TO \"$role\"")
                stmt.execute("GRANT ALL ON SCHEMA \"$schema\" TO \"$role\"")
                stmt.execute("GRANT ALL ON ALL TABLES IN SCHEMA \"$schema\" TO \"$role\"")
                stmt.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA \"$schema\" TO \"$role\"")
                stmt.execute("ALTER DEFAULT PRIVILEGES FOR ROLE \"$role\" IN SCHEMA \"$schema\" GRANT ALL ON TABLES TO \"$role\"")
            }
            provisioned += tenantId
        } catch (e: Exception) {
            if (warnedOnce.add(tenantId)) {
                log.warnf(
                    "Could not provision a dedicated database role for tenant %s (%s). Falling back " +
                        "to the shared connection's privileges; database-level tenant isolation " +
                        "(NFR-S8) does not hold until this is fixed. The connecting database user " +
                        "needs CREATEROLE — see the configuration reference.",
                    tenantId, e.message
                )
            }
        }
    }

    /**
     * Runs [block] under [tenantId]'s own role so the database itself refuses cross-tenant access
     * regardless of what schema name the caller's SQL names. A no-op — runs [block] directly on
     * the connection's own privileges — when the role could not be provisioned (see above).
     */
    fun <T> withTenantRole(connection: Connection, tenantId: UUID, block: () -> T): T {
        if (!provisioned.contains(tenantId)) return block()
        val role = roleName(tenantId)
        connection.createStatement().use { it.execute("SET ROLE \"$role\"") }
        try {
            return block()
        } finally {
            runCatching { connection.createStatement().use { it.execute("RESET ROLE") } }
        }
    }
}
