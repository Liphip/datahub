package com.dataturntable.connectors

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Not used for queries (ConnectorLeaseService accesses this table via native SQL for
 * atomic lease acquisition); mapped only so Hibernate's test-profile schema generation
 * creates it alongside the Flyway-managed production schema (see V1__Initial_schema.sql).
 */
@Entity
@Table(name = "connector_runtime_leases")
class ConnectorRuntimeLease {
    @Id @Column(name = "connector_id") var connectorId: UUID? = null
    @Column(name = "runtime_role", nullable = false) var runtimeRole: String = ""
    @Column(name = "owner_instance_id", nullable = false) var ownerInstanceId: String = ""
    @Column(name = "lease_until", nullable = false) var leaseUntil: Instant = Instant.now()
    @Column(name = "fencing_token", nullable = false) var fencingToken: Long = 0
    @Column(name = "updated_at") var updatedAt: Instant = Instant.now()
}
