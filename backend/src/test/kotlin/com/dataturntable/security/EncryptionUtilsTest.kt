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
        assertTrue(cipher.startsWith("v2:"))

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

    @Test
    fun testAuthenticatedCiphertextRejectsTampering() {
        System.setProperty("connector.config.key", Base64.getEncoder().encodeToString(ByteArray(32)))
        val cipher = EncryptionUtils.encrypt("secret")
        val tampered = cipher.dropLast(1) + if (cipher.last() == 'A') "B" else "A"
        assertThrows(IllegalArgumentException::class.java) { EncryptionUtils.decrypt(tampered) }
    }
}
