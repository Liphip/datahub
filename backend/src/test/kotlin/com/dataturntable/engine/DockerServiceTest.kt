package com.dataturntable.engine

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled

import org.junit.jupiter.api.Assertions.*

@QuarkusTest
@Disabled("Requires Docker to be available")
class DockerServiceTest {
    
    @Inject
    lateinit var dockerService: DockerService
    
    @Test
    fun testDockerServiceInitialization() {
        assertNotNull(dockerService)
    }
    
    @Test
    fun testExecuteModuleWithImport() {
        val code = """
            |def fetch():
            |    return {"temperature": 25.5, "humidity": 60}
        """.trimMargin()
        
        // This test would require Docker to be running
    }
}
