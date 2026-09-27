package com.dataturntable.pipeline

import com.dataturntable.PanacheBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "pipeline_versions", uniqueConstraints = [UniqueConstraint(columnNames = ["pipeline_id", "version"])])
class PipelineVersion : PanacheBase() {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pipeline_id", nullable = false) var pipeline: Pipeline? = null
    @Column(nullable = false) var version: Int = 1
    @Column(name = "graph_definition", nullable = false, columnDefinition = "text") var graphDefinition: String = "{}"
    @Column(name = "schema_version", nullable = false) var schemaVersion: Int = 1
    @Column(name = "content_fingerprint", nullable = false) var contentFingerprint: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
