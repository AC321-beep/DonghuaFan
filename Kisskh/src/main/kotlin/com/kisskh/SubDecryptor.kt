package com.kisskh

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Subtitle decryption utility for KissKH.
 * Uses three different key/IV pairs (the last one is from newer extensions).
 */
object SubDecryptor {
    // Keys as UTF-8 strings
    private const val KEY1 = "AmSmZVcH93UQUezi"
    private const val KEY2 = "8056483646328763"
    private const val KEY3 = "sWODXX04QRTkHdlZ"

    // IVs as integer arrays (converted to big‑endian byte arrays)
    private val IV1 = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
    private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)
    private val IV3 = intArrayOf(946894696, 1634749029, 1127508082, 1396271183)

    /**
     * Decrypt a single Base64‑encoded encrypted subtitle line.
     * Tries all three key/IV pairs and returns the first successful result.
     * If all fail, returns an error message.
     */
    fun decrypt(encryptedB64: String): String {
        val keyIvPairs = listOf(
            Pair(KEY1.toByteArray(Charsets.UTF_8), IV1.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
            Pair(KEY3.toByteArray(Charsets.UTF_8), IV3.toByteArray())
        )
        val encryptedBytes = base64DecodeArray(encryptedB64)

        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                return decryptWithKeyIv(keyBytes, ivBytes, encryptedBytes)
            } catch (ex: Exception) {
                // Try next key/IV
            }
        }
        return "DECRYPT_FAILED: All keys/IVs failed"
    }

    private fun decryptWithKeyIv(
        keyBytes: ByteArray,
        ivBytes: ByteArray,
        encryptedBytes: ByteArray
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    /**
     * Process an entire encrypted subtitle file content (the .txt response).
     * Splits into chunks using the chunk marker regex (lines that are only numbers),
     * then decrypts each line inside each chunk.
     */
    fun decryptFullContent(encryptedContent: String): String {
        val chunkRegex = Regex("^\\d+$", RegexOption.MULTILINE)
        val chunks = encryptedContent.split(chunkRegex)
            .filter { it.isNotBlank() }
            .map { it.trim() }

        return chunks.mapIndexed { index, chunk ->
            if (chunk.isBlank()) return@mapIndexed ""
            val parts = chunk.split("\n")
            if (parts.isEmpty()) return@mapIndexed ""

            val header = parts.first()
            val text = parts.drop(1)
            val decryptedLines = text.joinToString("\n") { line ->
                decrypt(line)
            }
            listOf(index + 1, header, decryptedLines).joinToString("\n")
        }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    // Helper to convert IntArray (big‑endian) to ByteArray
    private fun IntArray.toByteArray(): ByteArray {
        return ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }
    }
}
