package com.dataturntable.connectors

import java.util.UUID

/** Reference metadata is safe to return to editors; credential values never belong here. */
object ConnectorSecretReferences {
    fun validationError(value: Any?): String? {
        val refs = value as? Map<*, *> ?: return "Connector secretRefs must be a JSON object"
        for ((field, raw) in refs) {
            if (field !is String || !field.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
                return "Invalid connector secret reference field"
            }
            val ref = raw as? Map<*, *> ?: return "Invalid connector secret reference: expected id and optional version"
            if (ref.keys.any { it !in setOf("id", "version") }) return "Connector secret references may contain only id and version"
            val id = ref["id"] as? String
            if (id == null || runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false).not()) {
                return "Invalid connector secret reference ID"
            }
            if (ref.containsKey("version")) {
                val version = ref["version"] as? Number ?: return "Invalid connector secret reference version"
                if (version.toDouble() != version.toInt().toDouble() || version.toInt() < 1) {
                    return "Invalid connector secret reference version"
                }
            }
        }
        return null
    }
}
