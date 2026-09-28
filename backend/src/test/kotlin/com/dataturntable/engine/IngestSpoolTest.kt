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
    fun aRecordCountFailingMidSegmentDoesNotResendOrRecountRecordsThatAlreadySucceeded() {
        // Kafka accepts a prefix of the segment, then refuses again — the size() counter must
        // reflect only the records actually still unsent, and a later retry must resume past
        // what already succeeded rather than resending (and re-decrementing) it. Before the
        // fix, progress was only checkpointed every 500 records, so a shorter segment like this
        // one resent (and re-decremented) record 0 on every retry, silently driving size() below
        // the true remaining count — enough retries make isEmpty() true while unsent records
        // remain, permanently stranding them (scheduledReplay() never calls replay() again).
        repeat(3) { spool.append(IngestSpool.Entry("k", "m$it")) }
        val sentFirstAttempt = mutableListOf<String>()
        assertThrows(RuntimeException::class.java) {
            spool.replay { entry ->
                if (entry.message == "m2") throw RuntimeException("Kafka refused again")
                sentFirstAttempt += entry.message
            }
        }
        assertEquals(listOf("m0", "m1"), sentFirstAttempt)
        assertEquals(1, spool.size(), "only the one record that never sent should remain counted")

        val sentSecondAttempt = mutableListOf<String>()
        assertEquals(1, spool.replay { sentSecondAttempt += it.message })
        assertEquals(listOf("m2"), sentSecondAttempt, "the second attempt must not resend m0/m1")
        assertTrue(spool.isEmpty())
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
