package com.dataturntable.connectors

import com.fasterxml.jackson.databind.JsonNode

/**
 * Defines the interface `DataConnector`, the contract implemented by this source unit.
 */
interface DataConnector {
    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    fun connect(config: Map<String, Any>)
    /**
     * Removes, stops, or releases the requested state.
     */
    fun disconnect()
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isConnected(): Boolean
}

/**
 * Defines the interface `InputConnector`, the contract implemented by this source unit.
 */
interface InputConnector : DataConnector {
    /**
     * Retrieves or enumerates the requested state.
     */
    fun read(): List<Map<String, Any>>
    /**
     * Retrieves or enumerates the requested state.
     */
    fun readOne(): Map<String, Any>?
}

/**
 * Defines the interface `OutputConnector`, the contract implemented by this source unit.
 */
interface OutputConnector : DataConnector {
    /**
     * Delivers or routes the named data to its configured destination.
     */
    fun write(data: List<Map<String, Any>>)
    /**
     * Delivers or routes the named data to its configured destination.
     */
    fun writeOne(data: Map<String, Any>)
}
