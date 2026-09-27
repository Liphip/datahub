package com.dataturntable.execution

import com.dataturntable.PanacheBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "executions")
class Execution : PanacheBase() {
    @Id var id: UUID? = null
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID? = null
    @Column(name = "module_version_id", nullable = false) var moduleVersionId: UUID? = null
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ExecutionStatus = ExecutionStatus.PENDING
    @Column(name = "backend", nullable = false) var backend: String = "unassigned"
    @Column(name = "runtime_image", nullable = false) var runtimeImage: String = ""
    @Column(name = "runtime_image_digest") var runtimeImageDigest: String? = null
    @Column(name = "correlation_id") var correlationId: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now()
}
