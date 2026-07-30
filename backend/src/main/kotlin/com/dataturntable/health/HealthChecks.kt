package com.dataturntable.health

import com.dataturntable.config.RuntimeRoleConfig
import com.dataturntable.engine.WarmContainerPool
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness
import org.eclipse.microprofile.health.Startup
import org.jboss.logging.Logger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes

@ApplicationScoped
class DatabaseHealthCheck : HealthCheck {

    private val LOG = Logger.getLogger(DatabaseHealthCheck::class.java)

    @Inject
    lateinit var datasource: DataSource

    @Readiness
    override fun call(): HealthCheckResponse {
        return try {
            val startedAt = System.nanoTime()
            val connection = datasource.connection
            try {
                val valid = connection.isValid(5)
                val latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
                if (valid) {
                    HealthCheckResponse.builder()
                        .name("database")
                        .up()
                        .withData("databaseProduct", connection.metaData.databaseProductName)
                        .withData("latencyMs", latencyMs)
                        .build()
                } else {
                    HealthCheckResponse.builder()
                        .name("database")
                        .down()
                        .withData("latencyMs", latencyMs)
                        .build()
                }
            } finally {
                connection.close()
            }
        } catch (e: Exception) {
            LOG.error("Database health check failed", e)
            HealthCheckResponse.builder()
                .name("database")
                .down()
                .withData("error", e.message ?: "unknown")
                .build()
        }
    }
}

@ApplicationScoped
class KafkaHealthCheck : HealthCheck {

    private val LOG = Logger.getLogger(KafkaHealthCheck::class.java)

    @ConfigProperty(name = "quarkus.kafka.bootstrap.servers", defaultValue = "localhost:9092")
    lateinit var bootstrapServers: String

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Readiness
    override fun call(): HealthCheckResponse {
        if (!runtimeRoleConfig.requiresKafka()) {
            return HealthCheckResponse.builder()
                .name("kafka")
                .up()
                .withData("skipped", true)
                .withData("role", runtimeRoleConfig.role.name)
                .build()
        }
        return try {
            val endpoint = bootstrapServers.split(",")
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return HealthCheckResponse.down("kafka")
            checkSocket("kafka", endpoint)
        } catch (e: Exception) {
            LOG.error("Kafka health check failed", e)
            HealthCheckResponse.builder()
                .name("kafka")
                .down()
                .withData("error", e.message ?: "unknown")
                .build()
        }
    }
}

@ApplicationScoped
class RedisHealthCheck : HealthCheck {

    private val LOG = Logger.getLogger(RedisHealthCheck::class.java)

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    lateinit var redisHosts: String

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Readiness
    override fun call(): HealthCheckResponse {
        if (!runtimeRoleConfig.requiresRedis()) {
            return HealthCheckResponse.builder()
                .name("redis")
                .up()
                .withData("skipped", true)
                .withData("role", runtimeRoleConfig.role.name)
                .build()
        }
        return try {
            val endpoint = redisHosts.split(",")
                .mapNotNull(::parseRedisEndpoint)
                .firstOrNull()
                ?: return HealthCheckResponse.down("redis")
            checkSocket("redis", endpoint)
        } catch (e: Exception) {
            LOG.error("Redis health check failed", e)
            HealthCheckResponse.builder()
                .name("redis")
                .down()
                .withData("error", e.message ?: "unknown")
                .build()
        }
    }
}

@ApplicationScoped
class ApplicationStartupHealthCheck : HealthCheck {

    private val LOG = Logger.getLogger(ApplicationStartupHealthCheck::class.java)
    private val started = AtomicBoolean(false)
    private val startedAt = AtomicReference<Instant>()

    @Startup
    override fun call(): HealthCheckResponse {
        return try {
            if (started.get()) {
                HealthCheckResponse.builder()
                    .name("startup")
                    .up()
                    .withData("startedAt", startedAt.get()?.toString() ?: "unknown")
                    .build()
            } else {
                HealthCheckResponse.builder()
                    .name("startup")
                    .down()
                    .build()
            }
        } catch (e: Exception) {
            LOG.error("Startup health check failed", e)
            HealthCheckResponse.down("startup")
        }
    }

    fun markStarted() {
        started.set(true)
        startedAt.compareAndSet(null, Instant.now())
    }

    fun onStart(@Observes event: StartupEvent) {
        markStarted()
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

@ApplicationScoped
class ObservabilityHealthCheck : HealthCheck {

    @Inject
    lateinit var meterRegistry: MeterRegistry

    @Inject
    lateinit var warmContainerPool: WarmContainerPool

    @Inject
    lateinit var runtimeRoleConfig: RuntimeRoleConfig

    @Readiness
    override fun call(): HealthCheckResponse =
        HealthCheckResponse.builder()
            .name("observability")
            .up()
            .withData("meters", meterRegistry.meters.size.toLong())
            .withData("warmPoolEnabled", runtimeRoleConfig.ownsWarmPool() && warmContainerPool.poolEnabled)
            .withData("role", runtimeRoleConfig.role.name)
            .build()
}

private fun checkSocket(name: String, endpoint: String): HealthCheckResponse {
    val (host, port) = parseHostPort(endpoint)
    val startedAt = System.nanoTime()
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 3000)
    }
    val latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
    return HealthCheckResponse.builder()
        .name(name)
        .up()
        .withData("endpoint", "$host:$port")
        .withData("latencyMs", latencyMs)
        .build()
}

private fun parseRedisEndpoint(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    return if (trimmed.contains("://")) {
        val uri = URI.create(trimmed)
        if (uri.host == null || uri.port <= 0) null else "${uri.host}:${uri.port}"
    } else {
        trimmed
    }
}

private fun parseHostPort(endpoint: String): Pair<String, Int> {
    val trimmed = endpoint.trim()
    val host = trimmed.substringBeforeLast(":")
    val port = trimmed.substringAfterLast(":").toInt()
    return host to port
}
