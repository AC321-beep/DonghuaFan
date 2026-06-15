package com.livesports

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

object CryptoUtils {
    // Assuming you update your build.gradle.kts to use these names
    private val SECRET1 by lazy { BuildConfig.LIVE_SPORTS_SECRET1 }
    private val SECRET2 by lazy { BuildConfig.LIVE_SPORTS_SECRET2 }

    private val KEYS by lazy {
        mapOf(
            "key1" to parseKeyInfo(SECRET1),
            "key2" to parseKeyInfo(SECRET2)
        )
    }
    
    private fun parseKeyInfo(secret: String): KeyInfo {
        val parts = secret.split(":")
        return KeyInfo(
            key = hexStringToByteArray(parts[0]),
            iv = hexStringToByteArray(parts[1])
        )
    }
    
    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    fun decryptData(encryptedBase64: String): String? {
        return try {
            val cleanBase64 = encryptedBase64.trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
                .replace("\t", "")
            
            val ciphertext = base64DecodeArray(cleanBase64)
            
            for ((_, keyInfo) in KEYS) {
                val result = tryDecrypt(ciphertext, keyInfo)
                if (result != null) return result
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun tryDecrypt(ciphertext: ByteArray, keyInfo: KeyInfo): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(keyInfo.key, "AES")
            val ivParameterSpec = IvParameterSpec(keyInfo.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            
            val decrypted = cipher.doFinal(ciphertext)
            val text = String(decrypted, StandardCharsets.UTF_8)
            
            if (text.startsWith("{") || text.startsWith("[") || text.contains("http", ignoreCase = true)) {
                text
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
