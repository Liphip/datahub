package com.dataturntable.connectors

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.Optional

class DestinationPolicyTest {
    private fun policy(vararg networks: String) = DestinationPolicy(Optional.of(networks.toList()))
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `internal addresses are refused unless an administrator allows their network`() {
        val strict = DestinationPolicy(Optional.empty())
        listOf("127.0.0.1", "10.1.2.3", "192.168.0.10", "172.16.5.5", "169.254.10.10", "::1", "fd12::1").forEach {
            assertTrue(strict.prohibited(ip(it)), it)
        }
        assertFalse(strict.prohibited(ip("93.184.216.34")))
        assertThrows(DestinationPolicy.ProhibitedDestinationException::class.java) { strict.check("127.0.0.1") }

        val internal = policy("10.20.0.0/16", "192.168.5.7")
        assertFalse(internal.prohibited(ip("10.20.99.1")))
        assertTrue(internal.prohibited(ip("10.21.0.1")))
        assertFalse(internal.prohibited(ip("192.168.5.7")))
        assertTrue(internal.prohibited(ip("192.168.5.8")))
    }

    @Test
    fun `cloud metadata endpoints are refused even inside an allowed network`() {
        assertTrue(policy("169.254.0.0/16").prohibited(ip("169.254.169.254")))
    }
}
