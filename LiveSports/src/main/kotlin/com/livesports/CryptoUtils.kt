package com.livesports

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    /**
     * Decrypts an AES-CBC encrypted string payload.
     */
    fun decryptAesCbc(encryptedData: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decodedBytes = Base64.decode(encryptedData, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(decodedBytes)
        
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
