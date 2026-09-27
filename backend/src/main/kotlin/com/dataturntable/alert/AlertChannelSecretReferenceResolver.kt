package com.dataturntable.alert

import com.dataturntable.secrets.SecretService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

/** Resolves alert-channel secret references only for an in-memory delivery configuration. */
@ApplicationScoped
class AlertChannelSecretReferenceResolver {
    @Inject lateinit var secrets: SecretService

    fun resolve(channel: AlertChannel, config: Map<String, Any>): Map<String, Any> {
        val refs = config["secretRefs"] as? Map<*, *> ?: return config
        val tenantId = channel.tenant?.id ?: throw IllegalStateException("Alert channel tenant missing")
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
