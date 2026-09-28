package com.dataturntable.connectors

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
 * Not used for queries (PipelineResource/IngestResource access this table via native SQL);
 * mapped only so Hibernate's test-profile schema generation creates it alongside the
 * Flyway-managed production schema (see V5__merge_module_connector_update.sql).
 */
@Entity
@Table(name = "connector_pipeline_map", uniqueConstraints = [UniqueConstraint(columnNames = ["connector_id", "pipeline_id", "node_id"])])
class ConnectorPipelineMap {
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null
    @Column(name = "connector_id", nullable = false) var connectorId: UUID? = null
    @Column(name = "pipeline_id", nullable = false) var pipelineId: UUID? = null
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "node_type", nullable = false) var nodeType: String = ""
    @Column(name = "mqtt_topic") var mqttTopic: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
