package com.dataturntable.connectors

import com.fasterxml.jackson.databind.JsonNode

interface DataConnector {
    fun connect(config: Map<String, Any>)
    fun disconnect()
    fun isConnected(): Boolean
}

interface InputConnector : DataConnector {
    fun read(): List<Map<String, Any>>
    fun readOne(): Map<String, Any>?
}

interface OutputConnector : DataConnector {
    fun write(data: List<Map<String, Any>>)
    fun writeOne(data: Map<String, Any>)
}
