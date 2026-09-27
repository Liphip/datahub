package com.dataturntable.data

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Schema mapping for tenant table-registry metadata (mapped only so Hibernate's test-profile
 * drop-and-create schema generation creates this table alongside the Flyway-managed production
 * schema — DataResource accesses it via native SQL for the dialect-branched upsert, not this
 * entity directly).
 */
@Entity
@Table(name = "data_table_registry")
class DataTableRegistryRecord {
    @Id var id: UUID? = null
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID? = null
    @Column(name = "table_name", nullable = false) var tableName: String = ""
    @Column(name = "table_type", nullable = false) var tableType: String = ""
    @Column(name = "schema_def", columnDefinition = "jsonb") var schemaDef: String? = null
    @Column(name = "created_at") var createdAt: Instant = Instant.now()
    @Column(name = "updated_at") var updatedAt: Instant = Instant.now()
}
