package com.dataturntable.events

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class EventInboxServiceTest {
    @Inject lateinit var inbox: EventInboxService

    @Test
    @Transactional
    fun `claims an event only once`() {
        val id = UUID.randomUUID()
        assertTrue(inbox.claim(id))
        assertFalse(inbox.claim(id))
    }
}
