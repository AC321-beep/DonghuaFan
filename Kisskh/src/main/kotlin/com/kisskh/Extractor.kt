package com.kisskh

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ----------------------------------------------------------------------
// Video token generation (from the Python script)
// ----------------------------------------------------------------------
private const val VID_TOKEN = "62f176f3bb1b5b8e70e39932ad34a0c7"
private const val VIDEO_KEY_HEX = "4F6BDAA39E2F8CB07F5E722D9EDEF314"
private const val VIDEO_IV_HEX = "01504AF356E619CF2E42BBA68C3F70F9"

fun generateVideoKkey(episodeId: String): String {
    fun hashFunc(str: String): Int {
        var hash = 0
        for (ch in str) {
            val charCode = ch.code
            val shifted = (hash shl 5) and 0xFFFFFFFF
            hash = (shifted - hash + charCode).toInt()
        }
        return hash
    }

    val payload = mutableListOf("", episodeId, "", "mg3c3b04ba", "2.8.10", VID_TOKEN, "4830201", "kisskh", "kisskh", "kisskh", "kisskh", "kisskh", "kisskh", "00", "")
    val joined = payload.joinToString("|")
    val hashValue = hashFunc(joined)
    payload.add(1, hashValue.toString())
    val final = payload.joinToString("|")

    val key = hexStringToByteArray(VIDEO_KEY_HEX)
    val iv = hexStringToByteArray(VIDEO_IV_HEX)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    val encrypted = cipher.doFinal(final.toByteArray(Charsets.UTF_8))
    return encrypted.joinToString("") { "%02X".format(it) }
}

// ----------------------------------------------------------------------
// Subtitle token generation (using subs_token from the Python script)
// ----------------------------------------------------------------------
private const val SUBS_TOKEN = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"
private const val SUBS_KEY_HEX = "8F791C23F5BE50AFC7A342A2E212D9CF"   // Example – you must verify
private const val SUBS_IV_HEX = "4A9F2B3C8D7E1F6A5B4C3D2E1F0A9B8C"   // Example – you must verify

fun generateSubtitleKkey(episodeId: String): String {
    // Same algorithm as video, but with different static values.
    // The Python script did not include the subtitle key/iv, only the token.
    // You may need to extract them from the original Phisher98 extension or intercept a subtitle request.
    // For now, we return a placeholder – you must replace with the correct implementation.
    // TODO: Implement subtitle token generation using the subs_token and correct AES parameters.
    return ""
}
