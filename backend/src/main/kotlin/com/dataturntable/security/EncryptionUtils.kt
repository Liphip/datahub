package com.dataturntable.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import jakarta.enterprise.inject.spi.DeploymentException

/**
 * Defines the object `EncryptionUtils`, the contract implemented by this source unit.
 */
object EncryptionUtils {
    private const val AES = "AES"
    private const val AES_CBC = "AES/CBC/PKCS5Padding"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_PREFIX = "v2:"

    /**
     * Retrieves or enumerates the requested state.
     */
    private fun getKey(): ByteArray? {
        val raw = System.getenv("CONNECTOR_CONFIG_KEY") ?: System.getProperty("connector.config.key")
        if (raw == null || raw.isBlank()) {
            if (isProductionProfile()) {
                throw DeploymentException("CONNECTOR_CONFIG_KEY is required in production")
            }
            return null
        }

        // Prefer Base64-encoded key (common for CI / secrets)
        val decoded: ByteArray? = try {
            // Try decoding; if it fails, fall back to raw bytes
            java.util.Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

        val bytes = decoded ?: raw.toByteArray(Charsets.UTF_8)

        if (bytes.size != 32) {
            throw IllegalArgumentException("CONNECTOR_CONFIG_KEY must decode to exactly 32 bytes")
        }
        return bytes
    }

    private fun isProductionProfile(): Boolean =
        System.getProperty("quarkus.profile") == "prod" ||
            System.getenv("QUARKUS_PROFILE") == "prod"

    /**
     * Implements `encrypt` for this source unit.
     */
    fun encrypt(plain: String): String {
        val key = getKey() ?: return plain
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance(AES_GCM)
        val secretKey = SecretKeySpec(key, AES)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combo = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combo, 0, iv.size)
        System.arraycopy(encrypted, 0, combo, iv.size, encrypted.size)
        return GCM_PREFIX + Base64.getEncoder().encodeToString(combo)
    }

    /**
     * Implements `decrypt` for this source unit.
     */
    fun decrypt(cipherText: String): String {
        val key = getKey() ?: return cipherText
        if (cipherText.startsWith(GCM_PREFIX)) {
            try {
                val all = Base64.getDecoder().decode(cipherText.removePrefix(GCM_PREFIX))
                require(all.size > 12) { "Invalid authenticated ciphertext" }
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(128, all.copyOfRange(0, 12)))
                return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
            } catch (e: Exception) {
                throw IllegalArgumentException("Unable to decrypt authenticated connector configuration", e)
            }
        }
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
