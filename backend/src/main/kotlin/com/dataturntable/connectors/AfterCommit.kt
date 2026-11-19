package com.dataturntable.connectors

import io.quarkus.arc.Arc
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import org.jboss.logging.Logger
import io.micrometer.core.instrument.Metrics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs connection-manager updates once the current transaction has committed, so they see
 * the committed connector/pipeline rows and cannot roll the caller's transaction back.
 * Actions run one at a time on a dedicated thread with a fresh request context.
 *
 * That thread has no security identity. An action must therefore not reach through a
 * `@Authenticated` REST resource — not even to read one of its injected fields, because the
 * generated getter is intercepted and throws `UnauthorizedException` there. Capture the beans in
 * local values on the request thread and use those inside the action.
 */
@ApplicationScoped
class AfterCommit {

    private val log = Logger.getLogger(AfterCommit::class.java)
    /** Failed actions since start; also `dataturntable_connector_refresh_failures_total`. */
    private val failures = AtomicLong()
    internal fun failureCount(): Long = failures.get()

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "connector-refresh").apply { isDaemon = true } }

    @Inject
    lateinit var registry: TransactionSynchronizationRegistry

    fun run(action: () -> Unit) {
        val task = Runnable {
            // The managers read entities outside of transactions, which needs a request context.
            val requestContext = Arc.container().requestContext()
            requestContext.activate()
            try {
                action()
            } catch (e: Exception) {
                // With the stack trace: many exceptions here carry no message, and "failed: null"
                // alone made the cause impossible to find from a log.
                failures.incrementAndGet()
                Metrics.counter("dataturntable.connector.refresh.failures").increment()
                log.warn("Connection refresh failed: ${e.javaClass.name}: ${e.message}", e)
            } finally {
                requestContext.terminate()
            }
        }
        if (registry.transactionStatus == Status.STATUS_NO_TRANSACTION) {
            executor.execute(task)
            return
        }
        registry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() {}
            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) executor.execute(task)
            }
        })
    }

    /** Waits until every action queued so far has run (tests). */
    internal fun awaitIdle(timeoutSeconds: Long = 10): Boolean {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        return done.await(timeoutSeconds, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
