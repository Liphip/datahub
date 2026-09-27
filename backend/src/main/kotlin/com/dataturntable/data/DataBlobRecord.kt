package com.dataturntable.data

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Schema mapping for blob metadata; content stays in the configured storage backend. */
@Entity
@Table(name = "data_blobs")
class DataBlobRecord {
    @Id var id: UUID? = null
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID? = null
    @Column(nullable = false) var name: String = ""
    @Column(name = "content_type") var contentType: String? = null
    @Column(name = "size_bytes") var sizeBytes: Long? = null
    @Column(name = "storage_path") var storagePath: String? = null
    @Column(nullable = false) var status: String = "AVAILABLE"
    @Column(name = "last_error") var lastError: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now()
}
