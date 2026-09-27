package com.dataturntable.security

import jakarta.enterprise.context.ApplicationScoped

/** Redacts likely credentials from operational output before it leaves the service. */
@ApplicationScoped
class SensitiveDataRedactor {
    private val keyedValue = Regex(
        """(?i)((?:[\"']?(?:password|secret|token|api[_-]?key|authorization)[\"']?)\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,}\]]+)"""
    )
    private val authorizationBearer = Regex("""(?i)((?:[\"']?authorization[\"']?)\s*[:=]\s*Bearer\s+)[A-Za-z0-9._~+\-/]+=*""")
    private val bearer = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+\-/]+=*""")
    private val urlCredentials = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]+@""")

    fun text(value: String?): String? = value
        ?.let { authorizationBearer.replace(it) { match -> "${match.groupValues[1]}[REDACTED]" } }
        ?.let { keyedValue.replace(it) { match -> "${match.groupValues[1]}[REDACTED]" } }
        ?.let { bearer.replace(it) { match -> "${match.groupValues[1]}[REDACTED]" } }
        ?.let { urlCredentials.replace(it) { match -> "${match.groupValues[1]}[REDACTED]@" } }

    fun value(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) ->
            val name = key?.toString() ?: ""
            name to if (isSensitiveKey(name)) "[REDACTED]" else value(item)
        }
        is List<*> -> value.map(::value)
        is String -> text(value)
        else -> value
    }

    fun isSensitiveKey(name: String): Boolean = listOf("password", "secret", "token", "apikey", "api_key", "authorization", "value")
        .any { name.lowercase().contains(it) }
}
