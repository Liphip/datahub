package com.dataturntable.auth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.Optional
import java.util.UUID

class LocalLoginPolicyTest {
    @Test fun defaultsAllowPasswordsButRestrictedModeRequiresAnExactAccount() {
        val account = UUID.randomUUID()
        assertTrue(LocalLoginPolicy("all", Optional.empty()).allows(account))
        val restricted = LocalLoginPolicy("break-glass-only", Optional.of(" $account "))
        assertTrue(restricted.allows(account))
        assertFalse(restricted.allows(UUID.randomUUID()))
    }

    @Test fun invalidPolicyCannotSilentlyEnableOrDisableLocalAccess() {
        for ((mode, ids) in listOf("typo" to "", "break-glass-only" to "", "all" to "not-a-uuid",
            "break-glass-only" to "1-1-1-1-1")) {
            assertThrows(IllegalArgumentException::class.java) { LocalLoginPolicy(mode, Optional.of(ids)) }
        }
    }
}
