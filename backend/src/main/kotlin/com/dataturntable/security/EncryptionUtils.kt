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
        val k = System.getenv("CONNECTOR_CONFIG_KEY") ?: System.getProperty("connector.config.key")
        return k?.toByteArray(Charsets.UTF_8)?.let { bytes ->
            if (bytes.size >= 16) bytes.copyOf(16) else bytes.copyOf(16)
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
