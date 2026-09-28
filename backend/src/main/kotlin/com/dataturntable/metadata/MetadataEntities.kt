package com.dataturntable.metadata

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** Kind of metadata object with the attributes, identifier schemes and relations it has. */
@Entity
@Table(name = "metadata_types", uniqueConstraints = [UniqueConstraint(columnNames = ["tenant_id", "type_key"])])
open class MetadataType {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID? = null

    /** Stable key used in pipelines and imports, e.g. `meter`, `market_location`. */
    @Column(name = "type_key", nullable = false, length = 100)
    var typeKey: String? = null

    @Column(nullable = false)
    var name: String? = null

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(name = "attribute_schema", columnDefinition = "text", nullable = false)
    var attributeSchema: String = "[]"

    @Column(name = "identifier_schemes", columnDefinition = "text", nullable = false)
    var identifierSchemes: String = "[]"

    @Column(name = "relation_schema", columnDefinition = "text", nullable = false)
    var relationSchema: String = "[]"

    /** Template the type was installed from, if any. */
    @Column(length = 100)
    var template: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

/** A device, meter, location, grid area, ... */
@Entity
@Table(name = "metadata_objects")
open class MetadataObject {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID? = null

    @Column(name = "type_id", nullable = false)
    var typeId: UUID? = null

    @Column(nullable = false)
    var name: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

/** Identifier of an object in a source system during a period (null bounds are open). */
@Entity
@Table(name = "metadata_identifiers", uniqueConstraints = [UniqueConstraint(columnNames = ["tenant_id", "scheme", "id_value", "valid_from"])])
open class MetadataIdentifier {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID? = null

    @Column(name = "object_id", nullable = false)
    var objectId: UUID? = null

    @Column(nullable = false, length = 100)
    var scheme: String? = null

    @Column(name = "id_value", nullable = false)
    var value: String? = null

    @Column(name = "valid_from")
    var validFrom: Instant? = null

    @Column(name = "valid_to")
    var validTo: Instant? = null
}

/** One version of an object's attributes. */
@Entity
@Table(name = "metadata_attributes", uniqueConstraints = [UniqueConstraint(columnNames = ["object_id", "valid_from"])])
open class MetadataAttributeVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "object_id", nullable = false)
    var objectId: UUID? = null

    @Column(columnDefinition = "text", nullable = false)
    var attributes: String = "{}"

    @Column(name = "valid_from")
    var validFrom: Instant? = null

    @Column(name = "valid_to")
    var validTo: Instant? = null

    /** Where the version came from: a pipeline, an import, a user. */
    @Column
    var source: String? = null

    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now()
}

/** Directed relation between two objects during a period, e.g. meter → market_location. */
@Entity
@Table(name = "metadata_relations", uniqueConstraints = [UniqueConstraint(columnNames = ["from_object_id", "relation", "to_object_id", "valid_from"])])
open class MetadataRelation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID? = null

    @Column(name = "from_object_id", nullable = false)
    var fromObjectId: UUID? = null

    @Column(nullable = false, length = 100)
    var relation: String? = null

    @Column(name = "to_object_id", nullable = false)
    var toObjectId: UUID? = null

    @Column(name = "valid_from")
    var validFrom: Instant? = null

    @Column(name = "valid_to")
    var validTo: Instant? = null

    @Column
    var source: String? = null
}

/** True when [at] lies in [from, to) with null meaning an open bound. */
fun validAt(from: Instant?, to: Instant?, at: Instant): Boolean =
    (from == null || !from.isAfter(at)) && (to == null || to.isAfter(at))
