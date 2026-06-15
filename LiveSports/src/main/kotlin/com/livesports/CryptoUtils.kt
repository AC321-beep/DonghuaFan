package com.livesports

import com.lagradost.cloudstream3.base64DecodeArray
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    
    // Paste your actual extracted Cricify Hex Keys here
    private val SECRET1 by lazy { "your_hex_key_1:your_hex_iv_1" }
    private val SECRET2 by lazy { "your_hex_key_2:your_hex_iv_2" }

    private val KEYS by lazy {
        mapOf(
            "key1" to parseKeyInfo(SECRET1),
            "key2" to parseKeyInfo(SECRET2)
        )
    }
    
    private fun parseKeyInfo(secret: String): KeyInfo {
        val parts = secret.split(":")
        if (parts.size < 2) return KeyInfo(ByteArray(16), ByteArray(16))
        
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
    
    // This is the specific function the compiler is looking for!
    fun decryptData(encryptedBase64: String): String? {
        return try {
            val cleanBase64 = encryptedBase64.trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
            
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
            
            // Validate it looks like a JSON array/object before returning
            if (text.startsWith("{") || text.startsWith("[")) text else null
        } catch (e: Exception) {
            null
        }
    }
}
