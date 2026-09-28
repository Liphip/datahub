package com.dataturntable.connectors

import io.quarkus.arc.Arc
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import org.jboss.logging.Logger
import java.util.concurrent.Executors

/**
 * Runs connection-manager updates once the current transaction has committed, so they see
 * the committed connector/pipeline rows and cannot roll the caller's transaction back.
 * Actions run one at a time on a dedicated thread with a fresh request context.
 */
@ApplicationScoped
class AfterCommit {

    private val log = Logger.getLogger(AfterCommit::class.java)
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
                log.warnf("Connection refresh failed: %s", e.message)
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

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
