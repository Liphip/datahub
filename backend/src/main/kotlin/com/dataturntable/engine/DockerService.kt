package com.dataturntable.engine

import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

@ApplicationScoped
class DockerService {
    
    private val LOG = Logger.getLogger(DockerService::class.java)
    private val objectMapper = ObjectMapper()
    
    @ConfigProperty(name = "dataturntable.docker.module.base-image", defaultValue = "python:3.11-slim")
    lateinit var baseImage: String
    
    @ConfigProperty(name = "dataturntable.docker.network", defaultValue = "dataturntable")
    lateinit var networkName: String

    @ConfigProperty(name = "dataturntable.docker.allow-network", defaultValue = "false")
    var allowNetwork: Boolean = false
    
    @ConfigProperty(name = "dataturntable.docker.resources.cpu-limit", defaultValue = "1.0")
    lateinit var cpuLimit: String
    
    @ConfigProperty(name = "dataturntable.docker.resources.memory-limit", defaultValue = "512m")
    lateinit var memoryLimit: String
    
    @ConfigProperty(name = "dataturntable.docker.timeout-seconds", defaultValue = "300")
    var timeoutSeconds: Int = 300
    
    fun executeModule(
        moduleVersion: ModuleVersion,
        inputData: Any?,
        pipelineId: String,
        runId: String,
        moduleType: ModuleType
    ): Any? {
        val containerName = "dt-${pipelineId}-${runId}-${moduleType.name.lowercase()}-${UUID.randomUUID()}"
        
        LOG.infof("Executing module %s in container %s", moduleVersion.module?.name, containerName)
        
        return try {
            val code = moduleVersion.code ?: throw IllegalStateException("Module code is empty")
            val requirements = moduleVersion.requirements ?: ""
            
            val result = runContainer(
                containerName = containerName,
                code = code,
                requirements = requirements,
                inputData = inputData,
                moduleType = moduleType
            )
            
            cleanupContainer(containerName)
            
            if (result.isNotBlank()) {
                try {
                    objectMapper.readValue(result, Any::class.java)
                } catch (e: Exception) {
                    result
                }
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.errorf("Module execution failed: %s", e.message)
            cleanupContainer(containerName)
            throw RuntimeException("Module execution failed: ${e.message}", e)
        }
    }
    
    private fun runContainer(
        containerName: String,
        code: String,
        requirements: String,
        inputData: Any?,
        moduleType: ModuleType
    ): String {
        val scriptContent = buildModuleScript(code, moduleType)
        
        val dockerArgs = mutableListOf(
            "docker", "run",
            "--rm",
            "--name", containerName
        )

        if (allowNetwork) {
            dockerArgs.addAll(listOf("--network", networkName))
        } else {
            dockerArgs.addAll(listOf("--network", "none"))
        }

        dockerArgs.addAll(listOf(
            "--user", "1000:1000",
            "--read-only",
            "--tmpfs", "/tmp",
            "--cpu-shares", (cpuLimit.toDouble() * 1024).toInt().toString(),
            "--memory", memoryLimit,
            "-e", "MODULE_CODE=$scriptContent",
            "-e", "MODULE_REQUIREMENTS=$requirements",
            "-e", "MODULE_TYPE=${moduleType.name}"
        ))
        
        inputData?.let {
            dockerArgs.add("-e")
            dockerArgs.add("INPUT_DATA=${it.toString()}")
        }
        
        dockerArgs.add(baseImage)
        dockerArgs.add("python3")
        dockerArgs.add("-c")
        dockerArgs.add("import sys; exec(__import__('base64').b64decode(__import__('os').environ['MODULE_CODE']).decode())")
        
        return try {
            val processBuilder = ProcessBuilder(dockerArgs)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                throw RuntimeException("Module execution timed out after $timeoutSeconds seconds")
            }
            
            var line: String? = reader.readLine()
            while (line != null) {
                output.append(line).append("\n")
                line = reader.readLine()
            }
            
            val exitCode = process.exitValue()
            
            if (exitCode != 0) {
                throw RuntimeException("Container exited with code $exitCode: $output")
            }
            
            output.toString().trim()
        } catch (e: Exception) {
            throw RuntimeException("Failed to run container: ${e.message}", e)
        }
    }
    
    private fun buildModuleScript(code: String, moduleType: ModuleType): String {
        // Provide helper functions for modules in the execution environment.
        // alert_fn and query_fn are no-op stubs here; the platform can upgrade them to call back via APIs or messaging.
        val helperCode = """
            |import json
            |import os
            |
            |def alert_fn(severity='info', message='', payload=None):
            |    # Placeholder: platform should intercept this output and handle alert dispatch.
            |    print(json.dumps({'__alert__': {'severity': severity, 'message': message, 'payload': payload}}))
            |
            |def query_fn(query):
            |    # Placeholder for querying platform stored data
            |    return {}
        """.trimMargin()
        
        val script = when (moduleType) {
            ModuleType.IMPORT -> """
                |import sys
                |import json
                |$helperCode
                |$code
                |result = fetch() if 'fetch' in dir() else {}
                |print(json.dumps(result))
            """.trimMargin()
            
            ModuleType.PROCESSING -> """
                |import sys
                |import json
                |$helperCode
                |input_data = json.loads(__import__('os').environ.get('INPUT_DATA', '{}'))
                |$code
                |result = process(input_data) if 'process' in dir() else input_data
                |print(json.dumps(result))
            """.trimMargin()
            
            ModuleType.ALERT -> """
                |import sys
                |import json
                |$helperCode
                |input_data = json.loads(__import__('os').environ.get('INPUT_DATA', '{}'))
                |$code
                |result = evaluate(input_data) if 'evaluate' in dir() else False
                |print(json.dumps({"alert_fired": result}))
            """.trimMargin()
            
            ModuleType.EXPORT -> """
                |import sys
                |import json
                |$helperCode
                |input_data = json.loads(__import__('os').environ.get('INPUT_DATA', '{}'))
                |$code
                |send(input_data) if 'send' in dir() else None
                |print(json.dumps({"status": "sent"}))
            """.trimMargin()
        }
        
        return java.util.Base64.getEncoder().encodeToString(script.toByteArray())
    }
    
    private fun cleanupContainer(containerName: String) {
        try {
            val process = ProcessBuilder("docker", "rm", "-f", containerName)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        } catch (e: Exception) {
            LOG.warnf("Failed to cleanup container %s: %s", containerName, e.message)
        }
    }
    
    fun stopAllContainers() {
        LOG.info("Stopping all DataTurntable containers")
        
        try {
            val process = ProcessBuilder("docker", "ps", "-aq", "-f", "name=dt-")
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val containerIds = reader.readLines()
            
            containerIds.forEach { containerId ->
                if (containerId.isNotBlank()) {
                    ProcessBuilder("docker", "rm", "-f", containerId)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                }
            }
        } catch (e: Exception) {
            LOG.errorf("Failed to stop containers: %s", e.message)
        }
    }
}
