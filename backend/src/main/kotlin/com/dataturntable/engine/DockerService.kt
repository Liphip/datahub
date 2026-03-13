package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.LogStream
import com.spotify.docker.client.messages.Container
import com.spotify.docker.client.messages.ContainerConfig
import com.spotify.docker.client.messages.HostConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

@ApplicationScoped
class DockerService {
    
    private val LOG = Logger.getLogger(DockerService::class.java)
    private val objectMapper = ObjectMapper()
    
    @Inject
    lateinit var dockerClient: DockerClient
    
    @ConfigProperty(name = "dataturntable.docker.module.base-image", defaultValue = "python:3.11-slim")
    lateinit var baseImage: String
    
    @ConfigProperty(name = "dataturntable.docker.network", defaultValue = "dataturntable")
    lateinit var networkName: String
    
    @ConfigProperty(name = "dataturntable.docker.resources.cpu-limit", defaultValue = "1.0")
    lateinit var cpuLimit: String
    
    @ConfigProperty(name = "dataturntable.docker.resources.memory-limit", defaultValue = "512m")
    lateinit var memoryLimit: String
    
    private val activeContainers = mutableMapOf<String, Container>()
    
    fun executeModule(
        moduleVersion: ModuleVersion,
        inputData: JsonNode?,
        pipelineId: String,
        runId: String,
        moduleType: ModuleType
    ): String {
        val containerId = "dt-${moduleType.name.lowercase()}-${UUID.randomUUID()}"
        
        try {
            // Create user module image
            val imageId = buildModuleImage(moduleVersion, moduleType)
            
            // Run container
            val container = runContainer(imageId, containerId, moduleVersion, inputData, pipelineId, runId)
            
            activeContainers[containerId] = container
            
            // Wait for completion (with timeout)
            val exitCode = waitForContainer(container.id(), 300) // 5 minute timeout
            
            // Get logs
            val logs = getContainerLogs(container.id())
            
            // Cleanup
            cleanupContainer(container.id())
            activeContainers.remove(containerId)
            
            if (exitCode != 0) {
                throw RuntimeException("Module execution failed with exit code: $exitCode. Logs: $logs")
            }
            
            return logs
            
        } catch (e: Exception) {
            LOG.error("Error executing module: ${moduleVersion.module?.name}", e)
            activeContainers.remove(containerId)
            throw RuntimeException("Module execution failed: ${e.message}", e)
        }
    }
    
    private fun buildModuleImage(moduleVersion: ModuleVersion, moduleType: ModuleType): String {
        val dockerfile = generateDockerfile(moduleVersion, moduleType)
        
        LOG.infof("Building module image for: %s", moduleVersion.module?.name)
        
        // In production, use DockerClient.build() with proper caching
        // For now, return the base image
        return baseImage
    }
    
    private fun generateDockerfile(moduleVersion: ModuleVersion, moduleType: ModuleType): String {
        val requirements = moduleVersion.requirements ?: ""
        
        return """
            FROM $baseImage
            
            WORKDIR /app
            
            # Install dependencies
            RUN pip install --no-cache-dir pandas numpy
            
            # Add user requirements
            ${requirements.lines().filter { it.isNotBlank() }.joinToString("\n") { "RUN pip install --no-cache-dir $it" }}
            
            # Copy module code
            COPY module.py /app/module.py
            
            # Entry point
            CMD ["python", "-u", "module.py"]
        """.trimIndent()
    }
    
    private fun runContainer(
        imageId: String,
        containerId: String,
        moduleVersion: ModuleVersion,
        inputData: JsonNode?,
        pipelineId: String,
        runId: String
    ): Container {
        
        val env = listOf(
            "PIPELINE_ID=$pipelineId",
            "RUN_ID=$runId",
            "MODULE_TYPE=${moduleVersion.module?.moduleType?.name}",
            "INPUT_DATA=${inputData?.toString() ?: "{}"}"
        )
        
        val config = ContainerConfig.builder()
            .image(imageId)
            .hostname(containerId)
            .env(env)
            .cmd("python", "-c", moduleVersion.code ?: "print('No code')")
            .hostConfig(
                HostConfig.builder()
                    .networkMode(networkName)
                    .cpuCount(java.lang.Double.parseDouble(cpuLimit.replace("c", "")))
                    .memory(java.lang.Long.parseLong(memoryLimit.replace("m", "").replace("g", "")) * 1024 * 1024)
                    .autoRemove(true)
                    .build()
            )
            .build()
        
        return dockerClient.createContainer(config, containerId).also { container ->
            dockerClient.startContainer(container.id())
        }
    }
    
    private fun waitForContainer(containerId: String, timeoutSeconds: Int): Int {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000) {
            val info = dockerClient.inspectContainer(containerId)
            
            if (info.state().status() == "exited") {
                return info.state().exitCode() ?: -1
            }
            
            Thread.sleep(1000)
        }
        
        // Timeout - kill container
        dockerClient.killContainer(containerId)
        throw RuntimeException("Container execution timed out after $timeoutSeconds seconds")
    }
    
    private fun getContainerLogs(containerId: String): String {
        return try {
            dockerClient.logs(containerId, DockerClient.LogsParam.stdout(), DockerClient.LogsParam.stderr())
                .readFully()
        } catch (e: Exception) {
            LOG.warn("Could not fetch container logs", e)
            ""
        }
    }
    
    private fun cleanupContainer(containerId: String) {
        try {
            dockerClient.killContainer(containerId)
        } catch (e: Exception) {
            LOG.warn("Error killing container: $containerId", e)
        }
        
        try {
            dockerClient.removeContainer(containerId)
        } catch (e: Exception) {
            LOG.warn("Error removing container: $containerId", e)
        }
    }
    
    fun stopAllContainers() {
        activeContainers.values.forEach { container ->
            cleanupContainer(container.id())
        }
        activeContainers.clear()
    }
}
