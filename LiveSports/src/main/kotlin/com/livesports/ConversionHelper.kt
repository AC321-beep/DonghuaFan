package com.livesports

import android.util.Base64

fun String.base64ToHexOrNull(): String? {
    val raw = trim()
    if (raw.matches(Regex("^[0-9a-fA-F-]+$"))) return raw.replace("-", "").lowercase()
    return try {
        val decoded = Base64.decode(raw.replace('-', '+').replace('_', '/').let { v ->
            v + "=".repeat((4 - v.length % 4) % 4)
        }, Base64.DEFAULT)
        decoded.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }
}

fun String.hexToBase64UrlOrNull(): String? {
    val hex = trim().replace("-", "")
    if (hex.isEmpty() || hex.length % 2 != 0 || !hex.matches(Regex("^[0-9a-fA-F]+$"))) return null
    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
