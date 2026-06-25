package com.livesports

import android.util.Base64

// Hex & Base64 Converters
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
    return try {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        Base64.encodeToString(bytes, Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    } catch (_: Exception) { null }
}

// Data Models
data class LiveEventData(
    val id: Int, val title: String, val slug: String, val cat: String?,
    val formats: List<LiveEventFormat>?, val eventInfo: LiveEventInfo?
)
data class LiveEventFormat(val name: String?, val link: String?, val api: String?, val type: String?)
data class LiveEventInfo(
    val teamA: String?, val teamB: String?, val teamAFlag: String?, val teamBFlag: String?,
    val eventName: String?, val eventType: String?, val eventCat: String?, val eventLogo: String?,
    val startTime: String?, val endTime: String?
)
data class ChannelStreamResponse(
    val streamUrls: List<StreamUrl>?, val related: List<LiveEventData>?,
    val prevChannel: String?, val nextChannel: String?
)
data class StreamUrl(
    val api: String?, val id: Int?, val link: String?, val title: String?,
    val type: String?, val webLink: String?
)
data class LiveEventLoadData(
    val eventId: Int, val title: String, val poster: String, val slug: String,
    val formats: List<LiveEventFormat>, val eventInfo: LiveEventInfo?
)
data class IptvLoadData(
    val url: String, val title: String, val poster: String, val nation: String,
    val key: String, val keyid: String, val userAgent: String, val cookie: String,
    val licenseUrl: String, val drmKeys: Map<String, String>, val headers: Map<String, String>
)
