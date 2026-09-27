package com.dataturntable.engine

import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import jakarta.enterprise.inject.Any as AnyBean

@QuarkusTest
class IngestSpoolTest {

    @Inject
    lateinit var spool: IngestSpool

    @Inject
    @AnyBean
    lateinit var messaging: InMemoryConnector

    @BeforeEach
    fun clear() {
        spool.replay { }
        messaging.sink<String>("pipeline-commands").clear()
    }

    @AfterEach
    fun drain() {
        spool.replay { }
    }

    @Test
    fun spooledRecordsAreFsyncedAndReplayedInOrder() {
        repeat(3) { spool.append(IngestSpool.Entry("k$it", "m$it")) }
        assertEquals(3, spool.size())
        // On disk before replay: survives a restart.
        assertTrue(File(spool.spoolDir).listFiles()!!.any { it.name.startsWith("segment-") && it.length() > 0 })

        val sent = mutableListOf<String>()
        assertEquals(3, spool.replay { sent += it.message })
        assertEquals(listOf("m0", "m1", "m2"), sent)
        assertTrue(spool.isEmpty())
    }

    @Test
    fun failedReplayKeepsTheRecordsForTheNextAttempt() {
        repeat(2) { spool.append(IngestSpool.Entry("k", "m$it")) }
        assertThrows(RuntimeException::class.java) { spool.replay { throw RuntimeException("Kafka down") } }
        assertEquals(2, spool.size())
        val sent = mutableListOf<String>()
        spool.replay { sent += it.message }
        assertEquals(listOf("m0", "m1"), sent)
    }

    @Test
    fun scheduledReplayForwardsToKafkaWithTheOriginalKey() {
        spool.append(IngestSpool.Entry("pipeline:dev-1", """{"runId":"r"}"""))
        spool.scheduledReplay()
        val message = messaging.sink<String>("pipeline-commands").received().single()
        assertEquals("""{"runId":"r"}""", message.payload)
        assertEquals("pipeline:dev-1", message.getMetadata(OutgoingKafkaRecordMetadata::class.java).get().key)
        assertTrue(spool.isEmpty())
    }
}
