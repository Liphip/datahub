package com.dataturntable.events

import com.dataturntable.config.RuntimeRoleConfig
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ApplicationScoped
class EventOutboxDispatcher {
    private val log = Logger.getLogger(EventOutboxDispatcher::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dt-event-outbox").also { it.isDaemon = true }
    }

    @Inject lateinit var outbox: EventOutboxService
    @Inject lateinit var runtimeRoleConfig: RuntimeRoleConfig
    @ConfigProperty(name = "dataturntable.outbox.enabled", defaultValue = "true") var enabled: Boolean = true

    fun onStart(@Observes event: StartupEvent) {
        if (!enabled || !runtimeRoleConfig.requiresKafka()) return
        executor.scheduleWithFixedDelay(::dispatchSafely, 0, 1, TimeUnit.SECONDS)
    }

    fun onStop(@Observes event: ShutdownEvent) = executor.shutdownNow()

    private fun dispatchSafely() {
        try {
            outbox.dispatchPending()
        } catch (error: Exception) {
            log.warn("Event outbox dispatch failed; records remain pending", error)
        }
    }
}
