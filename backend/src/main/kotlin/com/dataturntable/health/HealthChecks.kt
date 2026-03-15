package com.dataturntable.health

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness
import org.eclipse.microprofile.health.Startup
import org.jboss.logging.Logger
import javax.sql.DataSource

@ApplicationScoped
class DatabaseHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(DatabaseHealthCheck::class.java)
    
    @Inject
    lateinit var datasource: DataSource
    
    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            val connection = datasource.connection
            try {
                connection.isValid(5)
                HealthCheckResponse.up("database")
            } finally {
                connection.close()
            }
        } catch (e: Exception) {
            LOG.error("Database health check failed", e)
            HealthCheckResponse.down("database")
        }
    }
}

@ApplicationScoped
class KafkaHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(KafkaHealthCheck::class.java)
    
    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            HealthCheckResponse.up("kafka")
        } catch (e: Exception) {
            LOG.error("Kafka health check failed", e)
            HealthCheckResponse.down("kafka")
        }
    }
}

@ApplicationScoped
class RedisHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(RedisHealthCheck::class.java)
    
    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            HealthCheckResponse.up("redis")
        } catch (e: Exception) {
            LOG.error("Redis health check failed", e)
            HealthCheckResponse.down("redis")
        }
    }
}

@ApplicationScoped
class ApplicationStartupHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(ApplicationStartupHealthCheck::class.java)
    private var started = true
    
    @Startup
    override fun call(): HealthCheckResponse {
        return try {
            if (started) {
                HealthCheckResponse.up("startup")
            } else {
                HealthCheckResponse.down("startup")
            }
        } catch (e: Exception) {
            LOG.error("Startup health check failed", e)
            HealthCheckResponse.down("startup")
        }
    }
    
    fun markStarted() {
        started = true
    }
}

@ApplicationScoped
class LivenessHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(LivenessHealthCheck::class.java)
    
    @Liveness
    override fun call(): HealthCheckResponse {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryPercent = (usedMemory.toDouble() / maxMemory.toDouble() * 100)
        
        return if (memoryPercent < 90) {
            HealthCheckResponse.builder()
                .name("memory")
                .up()
                .withData("used", "$usedMemory bytes")
                .withData("max", "$maxMemory bytes")
                .withData("percent", "$memoryPercent%")
                .build()
        } else {
            LOG.warnf("Memory usage high: %.2f%%", memoryPercent)
            HealthCheckResponse.builder()
                .name("memory")
                .down()
                .withData("used", "$usedMemory bytes")
                .withData("percent", "$memoryPercent%")
                .build()
        }
    }
}
