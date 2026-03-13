package com.dataturntable.connectors.http

import com.dataturntable.connectors.InputConnector
import com.dataturntable.connectors.OutputConnector
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.HttpResponse
import io.vertx.mutiny.ext.web.client.WebClient
import io.vertx.mutiny.ext.web.client.WebClientOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class HttpConnector {
    
    private val LOG = Logger.getLogger(HttpConnector::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var vertx: Vertx
    
    fun createRestInput(config: Map<String, Any>): HttpInputConnector {
        return HttpInputConnector(config, vertx)
    }
    
    fun createRestOutput(config: Map<String, Any>): HttpOutputConnector {
        return HttpOutputConnector(config, vertx)
    }
}

class HttpInputConnector(
    private val config: Map<String, Any>,
    private val vertx: Vertx
) : InputConnector {
    
    private val LOG = Logger.getLogger(HttpInputConnector::class.java)
    private val objectMapper = ObjectMapper()
    private var client: WebClient? = null
    
    override fun connect(config: Map<String, Any>) {
        val baseUrl = config["baseUrl"] as? String ?: throw IllegalArgumentException("baseUrl is required")
        val ssl = config["ssl"] as? Boolean ?: false
        val port = (config["port"] as? Number)?.toInt() ?: if (ssl) 443 else 80
        
        val options = WebClientOptions()
            .setDefaultHost(extractHost(baseUrl))
            .setDefaultPort(port)
            .setSsl(ssl)
            .setVerifyHost(config["verifyHost"] as? Boolean ?: true)
            .setTrustAll(config["trustAll"] as? Boolean ?: false)
        
        client = WebClient.create(vertx, options)
        LOG.infof("HTTP input connector connected to %s", baseUrl)
    }
    
    override fun read(): List<Map<String, Any>> {
        val url = config["url"] as? String ?: throw IllegalArgumentException("url is required")
        val method = config["method"] as? String ?: "GET"
        
        val request = when (method.uppercase()) {
            "GET" -> client?.get(url)
            "POST" -> client?.post(url)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }
        
        // Add headers
        (config["headers"] as? Map<String, String>)?.forEach { (key, value) ->
            request?.putHeader(key, value)
        }
        
        val response = request?.send()?.await()?.indefinitely()
        
        return if (response != null) {
            parseResponse(response)
        } else {
            emptyList()
        }
    }
    
    override fun readOne(): Map<String, Any>? {
        return read().firstOrNull()
    }
    
    private fun parseResponse(response: HttpResponse<*>): List<Map<String, Any>> {
        val body = response.bodyAsString()
        
        return try {
            when {
                body.startsWith("[") -> {
                    objectMapper.readValue(body, List::class.java) as? List<Map<String, Any>> ?: emptyList()
                }
                body.startsWith("{") -> {
                    listOf(objectMapper.readValue(body, Map::class.java) as? Map<String, Any> ?: emptyMap())
                }
                else -> {
                    listOf(mapOf("raw" to body))
                }
            }
        } catch (e: Exception) {
            LOG.error("Error parsing HTTP response", e)
            listOf(mapOf("raw" to body, "error" to e.message))
        }
    }
    
    override fun disconnect() {
        client = null
    }
    
    override fun isConnected(): Boolean = client != null
    
    private fun extractHost(url: String): String {
        return try {
            java.net.URI.create(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }
}

class HttpOutputConnector(
    private val config: Map<String, Any>,
    private val vertx: Vertx
) : OutputConnector {
    
    private val LOG = Logger.getLogger(HttpOutputConnector::class.java)
    private val objectMapper = ObjectMapper()
    private var client: WebClient? = null
    
    override fun connect(config: Map<String, Any>) {
        val baseUrl = config["baseUrl"] as? String ?: throw IllegalArgumentException("baseUrl is required")
        val ssl = config["ssl"] as? Boolean ?: false
        val port = (config["port"] as? Number)?.toInt() ?: if (ssl) 443 else 80
        
        val options = WebClientOptions()
            .setDefaultHost(extractHost(baseUrl))
            .setDefaultPort(port)
            .setSsl(ssl)
            .setVerifyHost(config["verifyHost"] as? Boolean ?: true)
            .setTrustAll(config["trustAll"] as? Boolean ?: false)
        
        client = WebClient.create(vertx, options)
        LOG.infof("HTTP output connector connected to %s", baseUrl)
    }
    
    override fun write(data: List<Map<String, Any>>) {
        data.forEach { item ->
            writeOne(item)
        }
    }
    
    override fun writeOne(data: Map<String, Any>) {
        val url = config["url"] as? String ?: throw IllegalArgumentException("url is required")
        val method = config["method"] as? String ?: "POST"
        val headers = config["headers"] as? Map<String, String> ?: emptyMap()
        
        val request = when (method.uppercase()) {
            "POST" -> client?.post(url)
            "PUT" -> client?.put(url)
            "PATCH" -> client?.patch(url)
            "GET" -> client?.get(url)
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }
        
        headers.forEach { (key, value) ->
            request?.putHeader(key, value)
        }
        
        request?.putHeader("Content-Type", "application/json")
        
        val body = try {
            objectMapper.writeValueAsString(data)
        } catch (e: Exception) {
            LOG.error("Error serializing data", e)
            return
        }
        
        val response = request?.sendBuffer(io.vertx.mutiny.core.buffer.Buffer.buffer(body))
            ?.await()?.indefinitely()
        
        if (response != null && response.statusCode() >= 400) {
            LOG.warnf("HTTP output error: %d - %s", response.statusCode(), response.bodyAsString())
        }
    }
    
    override fun disconnect() {
        client = null
    }
    
    override fun isConnected(): Boolean = client != null
    
    private fun extractHost(url: String): String {
        return try {
            java.net.URI.create(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }
}
