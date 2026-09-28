package com.dataturntable.service

import com.dataturntable.test.PostgresTestProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * NFR-S8 / TEN-03: proves isolation at the database layer itself, not only in application code —
 * against a real PostgreSQL instance, since role/grant semantics have no H2 equivalent (unlike
 * [PostgresMigrationBehaviorTest], which only needs Postgres for schema validation).
 */
@QuarkusTest
@TestProfile(PostgresTestProfile::class)
@org.junit.jupiter.api.condition.EnabledIf("com.dataturntable.test.PostgresTestProfile#available")
class TenantDatabaseRoleServiceTest {

    @Inject lateinit var service: TenantDatabaseRoleService
    @Inject lateinit var dataSource: DataSource

    private fun schemaOf(tenantId: UUID) = "tenant_${tenantId.toString().replace("-", "_")}"

    @Test
    fun `a tenant role is provisioned and owns its schema`() {
        val tenantId = UUID.randomUUID()
        val schema = schemaOf(tenantId)
        dataSource.connection.use { connection ->
            service.ensureRoleAndSchema(connection, tenantId, schema)
            connection.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = '$schema'")
                assertTrue(rs.next())
                assertEquals(service.roleName(tenantId), rs.getString(1))
            }
        }
    }

    @Test
    fun `a tenant's role is refused access to another tenant's schema, even when the SQL names it explicitly`() {
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val schemaA = schemaOf(tenantA)
        val schemaB = schemaOf(tenantB)
        dataSource.connection.use { connection ->
            service.ensureRoleAndSchema(connection, tenantA, schemaA)
            service.ensureRoleAndSchema(connection, tenantB, schemaB)
            service.withTenantRole(connection, tenantB) {
                connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS \"$schemaB\".\"secret\" (id int)") }
            }

            // The application would only ever ask for tenant A's own schema, but a bug or a
            // malicious module config could name tenant B's schema instead. Under tenant A's own
            // role, Postgres itself — not application code — refuses that, which is the point.
            val denied = assertThrows(SQLException::class.java) {
                service.withTenantRole(connection, tenantA) {
                    connection.createStatement().use { it.executeQuery("SELECT * FROM \"$schemaB\".\"secret\"") }
                }
            }
            assertTrue(denied.message?.contains("permission denied") == true, "unexpected message: ${denied.message}")
        }
    }

    @Test
    fun `a tenant can read and write its own schema under its own role`() {
        val tenantId = UUID.randomUUID()
        val schema = schemaOf(tenantId)
        dataSource.connection.use { connection ->
            service.ensureRoleAndSchema(connection, tenantId, schema)
            service.withTenantRole(connection, tenantId) {
                connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS \"$schema\".\"t\" (id int)") }
                connection.createStatement().use { it.execute("INSERT INTO \"$schema\".\"t\" VALUES (1)") }
                connection.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT id FROM \"$schema\".\"t\"")
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `re-provisioning an already-granted tenant is idempotent`() {
        val tenantId = UUID.randomUUID()
        val schema = schemaOf(tenantId)
        dataSource.connection.use { connection ->
            service.ensureRoleAndSchema(connection, tenantId, schema)
            service.ensureRoleAndSchema(connection, tenantId, schema) // second call must not throw
            service.withTenantRole(connection, tenantId) {
                connection.createStatement().use { it.execute("SELECT 1") }
            }
        }
    }

    @Test
    fun `provisioning on a non-autoCommit connection survives a later rollback on the same connection`() {
        // PipelineDataService runs ensureRoleAndSchema on a connection with autoCommit = false,
        // then reuses that same connection for the DB_LOAD/DB_SAVE work and commits at the very
        // end. If the role/schema DDL weren't durably committed on its own, a later failure in
        // that same transaction (simulated here with an explicit rollback) would silently undo
        // the provisioning while the in-memory `provisioned` cache kept claiming it succeeded —
        // every later call for this tenant would then try to SET ROLE to a role that doesn't
        // exist, broken until the process restarts.
        val tenantId = UUID.randomUUID()
        val schema = schemaOf(tenantId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            service.ensureRoleAndSchema(connection, tenantId, schema)
            connection.rollback() // simulates the caller's own subsequent work failing
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = '$schema'")
                assertTrue(rs.next(), "schema $schema should have survived the rollback on the provisioning connection")
                assertEquals(service.roleName(tenantId), rs.getString(1))
            }
            // The cache correctly still says this tenant is provisioned, and using it actually works.
            service.withTenantRole(connection, tenantId) {
                connection.createStatement().use { it.execute("SELECT 1") }
            }
        }
    }

    @Test
    fun `a failed provisioning attempt leaves a non-autoCommit connection usable for the fallback`() {
        // Simulates the CREATEROLE-denied case without needing a second, unprivileged database
        // user: forces ensureRoleAndSchema's own DDL to fail by pre-creating the role as a role
        // this session cannot later ALTER (LOGIN, not the plain NOLOGIN the method creates,
        // fails silently on the IF NOT EXISTS guard) -- instead, directly exercise the failure
        // path by asking it to provision an invalid schema name that CREATE SCHEMA rejects.
        val tenantId = UUID.randomUUID()
        val invalidSchema = "" // CREATE SCHEMA "" is a syntax/identifier error on PostgreSQL
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            service.ensureRoleAndSchema(connection, tenantId, invalidSchema) // logs a warning, does not throw
            // Before the fix, this next statement would fail with "current transaction is
            // aborted, commands ignored until end of transaction block" - proving the fallback
            // (running without database-level isolation) actually works, not just that the
            // caller stops throwing.
            connection.createStatement().use { it.execute("SELECT 1") }
            connection.commit()
        }
    }
}
