package com.dataturntable.connectors

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Immutable encrypted configuration snapshot for a connector change. */
@Entity
@Table(name = "connector_revisions")
class ConnectorRevision {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "connector_id", nullable = false) var connectorId: UUID? = null
    @Column(name = "revision", nullable = false) var revision: Int = 0
    @Column(name = "snapshot", nullable = false, columnDefinition = "TEXT") var snapshot: String = "{}"
    @Column(name = "created_by") var createdBy: UUID? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
