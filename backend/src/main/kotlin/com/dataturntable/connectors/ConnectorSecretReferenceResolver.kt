package com.dataturntable.connectors

import com.dataturntable.secrets.SecretService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

/** Resolves persisted connector secret references only inside runtime memory. */
@ApplicationScoped
class ConnectorSecretReferenceResolver {
    @Inject lateinit var secrets: SecretService

    fun resolve(connector: Connector, config: Map<String, Any?>): Map<String, Any?> {
        if (!config.containsKey("secretRefs")) return config
        ConnectorSecretReferences.validationError(config["secretRefs"])?.let {
            throw IllegalArgumentException("$it. Re-enter and save connector credentials.")
        }
        val refs = config["secretRefs"] as Map<*, *>
        val tenantId = connector.tenant?.id ?: throw IllegalStateException("Connector tenant missing")
        val resolved = config.toMutableMap()
        refs.forEach { (fieldRaw, refRaw) ->
            val field = fieldRaw?.toString()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Invalid secret reference field")
            val ref = refRaw as? Map<*, *> ?: throw IllegalArgumentException("Invalid secret reference for $field")
            val id = runCatching { UUID.fromString(ref["id"]?.toString()) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid secret ID for $field")
            val version = (ref["version"] as? Number)?.toInt()
            resolved[field] = secrets.resolve(tenantId, id, version)
        }
        resolved.remove("secretRefs")
        return resolved
    }
}
