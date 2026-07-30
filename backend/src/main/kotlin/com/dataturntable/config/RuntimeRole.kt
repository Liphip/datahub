package com.dataturntable.config

enum class RuntimeRole {
    CONTROL_API,
    PUBLIC_API,
    INGEST_EDGE,
    PIPELINE_EXECUTOR,
    ALL;

    companion object {
        fun fromConfig(value: String?): RuntimeRole =
            entries.firstOrNull { it.name.equals(value?.replace('-', '_'), ignoreCase = true) } ?: ALL
    }
}
