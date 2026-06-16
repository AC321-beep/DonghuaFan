package com.livesports

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val keys by lazy {
        listOf(
            try { BuildConfig.LIVESPORTS_PROVIDER_SECRET1 } catch (e: Exception) { "" },
            try { BuildConfig.LIVESPORTS_PROVIDER_SECRET2 } catch (e: Exception) { "" }
        ).filter { it.isNotBlank() }.mapNotNull { parseKeyInfo(it) }
    }

    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)

    private fun parseKeyInfo(secret: String): KeyInfo? {
        val parts = secret.split(":")
        if (parts.size != 2) return null
        return KeyInfo(hexToBytes(parts[0]), hexToBytes(parts[1]))
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    // 100% exact match to the Smali logic you extracted
    fun decryptData(encryptedBase64: String): String? {
        // Explicitly replace newlines, carriage returns, spaces, and tabs just like the Smali
        val cleanBase64 = encryptedBase64.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")

        val ciphertext = base64DecodeArray(cleanBase64) ?: return null

        for (keyInfo in keys) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKeySpec = SecretKeySpec(keyInfo.key, "AES")
                val ivParameterSpec = IvParameterSpec(keyInfo.iv)
                
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
                
                val decryptedBytes = cipher.doFinal(ciphertext)
                val text = String(decryptedBytes, Charsets.UTF_8)
                
                // Matches the Smali validation exactly, including ignoreCase = true for "http"
                if (text.startsWith("{") || 
                    text.startsWith("[") || 
                    text.contains("http", ignoreCase = true)) {
                    return text
                }
            } catch (_: Exception) { 
                // Silently loop to the next key on failure, just like the Smali
            }
        }
        return null
    }
}
