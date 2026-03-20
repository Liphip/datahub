package com.dataturntable.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object EncryptionUtils {
    private const val AES = "AES"
    private const val AES_CBC = "AES/CBC/PKCS5Padding"

    private fun getKey(): ByteArray? {
        val raw = System.getenv("CONNECTOR_CONFIG_KEY") ?: System.getProperty("connector.config.key")
        if (raw == null || raw.isBlank()) return null

        // Prefer Base64-encoded key (common for CI / secrets)
        val decoded: ByteArray? = try {
            // Try decoding; if it fails, fall back to raw bytes
            java.util.Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

        val bytes = decoded ?: raw.toByteArray(Charsets.UTF_8)

        // Normalize to 16/24/32 bytes for AES key sizes (prefer 32)
        return when {
            bytes.size >= 32 -> bytes.copyOf(32)
            bytes.size >= 24 -> bytes.copyOf(24)
            bytes.size >= 16 -> bytes.copyOf(16)
            else -> {
                // pad with zeros
                val padded = ByteArray(16)
                System.arraycopy(bytes, 0, padded, 0, bytes.size)
                padded
            }
        }
    }

    fun encrypt(plain: String): String {
        val key = getKey() ?: return plain
        val iv = ByteArray(16)
        Random.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_CBC)
        val secretKey = SecretKeySpec(key, AES)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combo = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combo, 0, iv.size)
        System.arraycopy(encrypted, 0, combo, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combo)
    }

    fun decrypt(cipherText: String): String {
        val key = getKey() ?: return cipherText
        return try {
            val all = Base64.getDecoder().decode(cipherText)
            val iv = all.copyOfRange(0, 16)
            val enc = all.copyOfRange(16, all.size)
            val cipher = Cipher.getInstance(AES_CBC)
            val secretKey = SecretKeySpec(key, AES)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (e: Exception) {
            cipherText
        }
    }
}
