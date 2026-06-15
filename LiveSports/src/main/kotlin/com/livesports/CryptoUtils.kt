package com.livesports

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    // No secrets – decryption will fail (return null)
    private fun getSecret1() = ""
    private fun getSecret2() = ""

    private val keys = listOf(getSecret1(), getSecret2()).mapNotNull { parseKeyInfo(it) }

    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)

    private fun parseKeyInfo(secret: String): KeyInfo? {
        val parts = secret.split(":")
        if (parts.size != 2) return null
        return KeyInfo(hexToBytes(parts[0]), hexToBytes(parts[1]))
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun decryptData(encryptedBase64: String): String? {
        val clean = encryptedBase64.trim().replace(Regex("[\\n\\r\\s]"), "")
        val ciphertext = base64DecodeArray(clean) ?: return null
        for (keyInfo in keys) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyInfo.key, "AES"), IvParameterSpec(keyInfo.iv))
                val decrypted = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
                if (decrypted.startsWith("{") || decrypted.startsWith("[") || decrypted.contains("http"))
                    return decrypted
            } catch (_: Exception) { }
        }
        return null
    }
}
