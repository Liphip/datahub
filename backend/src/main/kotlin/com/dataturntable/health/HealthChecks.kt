package com.dataturntable.health

import io.smallrye.health.api.Wellness
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness
import org.eclipse.microprofile.health.Startup
import org.jboss.logging.Logger
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.redis.client.Redis

@ApplicationScoped
class DatabaseHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(DatabaseHealthCheck::class.java)
    
    @Inject
    lateinit var database: PgPool
    
    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            val result = database.query("SELECT 1").execute().await().indefinitely()
            if (result != null) {
                HealthCheckResponse.up("database")
            } else {
                HealthCheckResponse.down("database")
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
    
    // Simple check - in production would check Kafka broker connectivity
    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            // Would check Kafka connectivity here
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
    
    @Inject
    lateinit var redis: Redis
    
    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            redis.send(io.vertx.mutiny.redis.client.Request.cmd("PING"))
                .onItem().transform { HealthCheckResponse.up("redis") }
                .onFailure().recoverWithItem { e ->
                    LOG.error("Redis health check failed", e)
                    HealthCheckResponse.down("redis")
                }
                .await().indefinitely()
        } catch (e: Exception) {
            LOG.error("Redis health check failed", e)
            HealthCheckResponse.down("redis")
        }
    }
}

@ApplicationScoped
class ApplicationStartupHealthCheck : HealthCheck {
    
    private val LOG = Logger.getLogger(ApplicationStartupHealthCheck::class.java)
    private var started = false
    
    @Startup
    override fun call(): HealthCheckResponse {
        return try {
            if (started) {
                HealthCheckResponse.up("startup")
            } else {
                HealthCheckResponse.starting("startup")
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
