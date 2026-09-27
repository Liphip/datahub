package com.dataturntable.security

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Canonical evaluator for the legacy serialized ability columns.
 *
 * The columns remain text until the normalized ability migration, but their
 * contents are treated strictly as JSON arrays of exact permission tokens.
 * Invalid or non-array content is denied rather than interpreted loosely.
 */
object AbilityEvaluator {
    private val objectMapper = com.dataturntable.json.JsonObjectMappers.create()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun parse(serializedAbilities: String?): Set<String> {
        if (serializedAbilities.isNullOrBlank()) return emptySet()

        return try {
            val value = objectMapper.readValue(serializedAbilities, Any::class.java)
            (value as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun hasAbility(serializedAbilities: String?, requestedAbility: String): Boolean {
        val abilities = parse(serializedAbilities)
        return "*" in abilities || requestedAbility in abilities
    }
}
