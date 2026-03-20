package com.dataturntable.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class EncryptionUtilsTest {
    @Test
    fun testEncryptDecryptWithBase64Key() {
        // 32-byte key (all zeros) base64
        val key = Base64.getEncoder().encodeToString(ByteArray(32))
        System.setProperty("connector.config.key", key)

        val plain = "{\"host\":\"example.local\",\"port\":1883}"
        val cipher = EncryptionUtils.encrypt(plain)
        assertNotNull(cipher)
        assertNotEquals(plain, cipher)

        val decrypted = EncryptionUtils.decrypt(cipher)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testEncryptReturnsPlainWhenNoKey() {
        System.clearProperty("connector.config.key")
        val plain = "simple-string"
        val out = EncryptionUtils.encrypt(plain)
        assertEquals(plain, out)
    }
}
