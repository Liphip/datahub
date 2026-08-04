package com.dataturntable.config

/**
 * Defines the enum class `RuntimeRole`, the contract implemented by this source unit.
 */
enum class RuntimeRole {
    CONTROL_API,
    PUBLIC_API,
    INGEST_EDGE,
    PIPELINE_EXECUTOR,
    ALL;

    companion object {
        /**
         * Implements `fromConfig` for this source unit.
         */
        fun fromConfig(value: String?): RuntimeRole =
            entries.firstOrNull { it.name.equals(value?.replace('-', '_'), ignoreCase = true) } ?: ALL
    }
}
