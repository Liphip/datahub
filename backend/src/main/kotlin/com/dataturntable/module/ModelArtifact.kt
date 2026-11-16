package com.dataturntable.module

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * A fitted model a PROCESSING module saved (`save_model_fn`, MOD-15) so it — or another module in
 * the same or a different pipeline — can load it later (`load_model_fn`) without a separate
 * model-serving component. One row per version, like [com.dataturntable.connectors.ConnectorRevision]:
 * saving under a name already used by this module creates a new row rather than overwriting.
 *
 * `dataEncrypted` is opaque to the platform: base64 bytes the module produced (typically a pickled
 * scikit-learn/PyTorch object), then encrypted at rest exactly like a [com.dataturntable.secrets.SecretVersion].
 * This is deliberately not the unstructured-payload storage STR-08 excludes — it is internal state
 * one module's own execution writes and reads back for itself, never a user-facing file (DAT-10).
 */
@Entity
@Table(name = "model_artifacts", uniqueConstraints = [UniqueConstraint(columnNames = ["module_id", "name", "version"])])
class ModelArtifact {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID? = null
    @Column(name = "module_id", nullable = false) var moduleId: UUID? = null
    @Column(nullable = false, length = 128) var name: String = ""
    @Column(nullable = false) var version: Int = 0
    @Column(name = "data_encrypted", nullable = false, columnDefinition = "TEXT") var dataEncrypted: String = ""
    @Column(name = "size_bytes", nullable = false) var sizeBytes: Long = 0
    @Column(name = "created_by_run_id") var createdByRunId: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}

/** MOD-16: one row per (run, artifact version) the run's module actually loaded. */
@Entity
@Table(name = "model_artifact_reads", uniqueConstraints = [UniqueConstraint(columnNames = ["run_id", "module_id", "name", "version"])])
class ModelArtifactRead {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID? = null
    @Column(name = "module_id", nullable = false) var moduleId: UUID? = null
    @Column(nullable = false, length = 128) var name: String = ""
    @Column(nullable = false) var version: Int = 0
    @Column(name = "run_id", nullable = false, length = 64) var runId: String = ""
    @Column(name = "read_at", nullable = false) var readAt: Instant = Instant.now()
}
