package com.livesports  

import android.util.Base64
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONObject
import java.io.InputStream

/**
 * Exception thrown when an error occurs while parsing playlist.
 */
sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader : PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}

data class Playlist(
    val items: List<PlaylistItem> = emptyList(),
)

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
    val key: String? = null,
    val keyid: String? = null,
    val cookie: String? = null,
    val licenseUrl: String? = null,
    val drmKeys: Map<String, String> = emptyMap(),
)

class IptvPlaylistParser {

    private fun String.hexOrNull(): String? {
        val normalizedHex = replace("-", "").trim()
        if (normalizedHex.isBlank() || normalizedHex.length % 2 != 0) return null
        return if (normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
            normalizedHex.lowercase()
        } else {
            null
        }
    }

    private fun String.base64ToHexOrNull(): String? {
        val normalized = trim()
            .replace('-', '+')
            .replace('_', '/')
            .let { value ->
                val padding = (4 - (value.length % 4)) % 4
                value + "=".repeat(padding)
            }

        return try {
            val decoded = Base64.decode(normalized, Base64.DEFAULT)
            decoded.joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.normalizeDrmHexOrNull(): String? {
        val trimmed = trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null
        return trimmed.hexOrNull() ?: trimmed.base64ToHexOrNull()
    }

    private fun parseLicenseKeysMap(licenseKey: String): Map<String, String> {
        val trimmedKey = licenseKey.trim()
        if (!trimmedKey.startsWith("{")) return emptyMap()

        return try {
            val json = JSONObject(trimmedKey)
            val keys = json.optJSONArray("keys") ?: return emptyMap()
            val parsed = mutableMapOf<String, String>()

            for (index in 0 until keys.length()) {
                val item = keys.optJSONObject(index) ?: continue
                val kid = item.optString("kid").normalizeDrmHexOrNull()
                val key = item.optString("k").normalizeDrmHexOrNull()

                if (!kid.isNullOrEmpty() && !key.isNullOrEmpty()) {
                    parsed[kid] = key
                }
            }

            parsed
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseLicenseKeyPair(licenseKey: String): Pair<String?, String?>? {
        val trimmedKey = licenseKey.trim()
        if (trimmedKey.isEmpty()) return null

        if (trimmedKey.startsWith("{")) {
            return try {
                val json = JSONObject(trimmedKey)
                val keys = json.optJSONArray("keys") ?: return null

                for (index in 0 until keys.length()) {
                    val item = keys.optJSONObject(index) ?: continue
                    val kid = item.optString("kid").normalizeDrmHexOrNull()
                    val key = item.optString("k").normalizeDrmHexOrNull()

                    if (kid != null || key != null) {
                        return key to kid
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        val parts = when {
            trimmedKey.contains(":") -> trimmedKey.split(":", limit = 2)
            trimmedKey.contains(",") -> trimmedKey.split(",", limit = 2)
            else -> emptyList()
        }

        if (parts.size != 2) return null

        val keyId = parts[0].trim().normalizeDrmHexOrNull()
        val key = parts[1].trim().normalizeDrmHexOrNull()

        return key to keyId
    }

    /**
     * Parse M3U8 string into [Playlist]
     *
     * @param content M3U8 content string.
     * @throws PlaylistParserException if an error occurs.
     */
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    /**
     * Parse M3U8 content [InputStream] into [Playlist]
     *
     * @param input Stream of input data.
     * @throws PlaylistParserException if an error occurs.
     */
    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val allLines = input.bufferedReader().readLines()
        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var i = 0

        // Buffer for all properties - accumulate until URL line is found
        var bufferedCookie: String? = null
        var bufferedUserAgent: String? = null
        var bufferedHeaders: Map<String, String> = emptyMap()
        var bufferedKey: String? = null
        var bufferedKeyId: String? = null
        var bufferedLicenseUrl: String? = null
        var bufferedDrmKeys: Map<String, String> = emptyMap()
        var bufferedTitle: String? = null
        var bufferedAttributes: Map<String, String> = emptyMap()

        while (i < allLines.size) {
            val line = allLines[i].trim()

            if (line.isNotEmpty()) {
                when {
                    line.startsWith("#EXTINF") -> {
                        bufferedTitle = line.getTitle()
                        bufferedAttributes = line.getAttributes()

                        // Extract DRM keys from attributes if present
                        val keyFromAttr = bufferedAttributes["key"] ?: bufferedAttributes["drm-key"]
                        val keyidFromAttr = bufferedAttributes["keyid"] ?: bufferedAttributes["drm-keyid"] ?: bufferedAttributes["kid"]

                        // Only use attribute keys if no buffered keys exist
                        if (bufferedKey == null) bufferedKey = keyFromAttr
                        if (bufferedKeyId == null) bufferedKeyId = keyidFromAttr
                    }
                    line.startsWith("#EXTHTTP:") -> {
                        // Parse JSON for cookie and user-agent, buffer them
                        val json = line.removePrefix("#EXTHTTP:").trim()
                        try {
                            val map = parseJson<Map<String, String>>(json)
                            if (map.containsKey("cookie")) {
                                bufferedCookie = map["cookie"]
                            }
                            if (map.containsKey("user-agent")) {
                                bufferedUserAgent = map["user-agent"]
                            }
                        } catch (e: Exception) {
                            // Ignore parsing errors
                        }
                    }
                    line.startsWith("#EXTVLCOPT") -> {
                        // Buffer user agent and referrer
                        val userAgent = line.getTagValue("http-user-agent")
                        val referrer = line.getTagValue("http-referrer") ?: line.getTagValue("http-referer")

                        if (userAgent != null) bufferedUserAgent = userAgent
                        if (referrer != null) {
                            bufferedHeaders = bufferedHeaders + mapOf("Referer" to referrer)
                        }
                    }
                    line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                        // Parse keyid and key from license_key and buffer them
                        val licenseKey = line.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()

                        // Check if license key is a URL
                        if (licenseKey.startsWith("http://") || licenseKey.startsWith("https://")) {
                            bufferedLicenseUrl = licenseKey
                        } else {
                            if (licenseKey.startsWith("{")) {
                                // Updated JSON logic: preserve all KID->KEY pairs and use first as fallback.
                                val parsedKeys = parseLicenseKeysMap(licenseKey)
                                if (parsedKeys.isNotEmpty()) {
                                    bufferedDrmKeys = parsedKeys
                                    val firstPair = parsedKeys.entries.firstOrNull()
                                    if (firstPair != null) {
                                        if (bufferedKey == null) bufferedKey = firstPair.value
                                        if (bufferedKeyId == null) bufferedKeyId = firstPair.key
                                    }
                                }

                                val parsedKeyPair = parseLicenseKeyPair(licenseKey)
                                if (parsedKeyPair != null) {
                                    val (key, keyId) = parsedKeyPair
                                    if (key != null) bufferedKey = key
                                    if (keyId != null) bufferedKeyId = keyId
                                }
                            } else {
                                // Legacy non-JSON logic: split keyid:key or keyid,key and decode hex -> base64url.
                                val parts = when {
                                    licenseKey.contains(":") -> licenseKey.split(":")
                                    licenseKey.contains(",") -> licenseKey.split(",")
                                    else -> listOf(licenseKey)
                                }

                                val drmKidBytes = parts.getOrNull(0)
                                    ?.replace("-", "")
                                    ?.chunked(2)
                                    ?.mapNotNull {
                                        try { it.toInt(16).toByte() }
                                        catch (_: NumberFormatException) { null }
                                    }?.toByteArray()

                                val drmKeyBytes = parts.getOrNull(1)
                                    ?.replace("-", "")
                                    ?.chunked(2)
                                    ?.mapNotNull {
                                        try { it.toInt(16).toByte() }
                                        catch (_: NumberFormatException) { null }
                                    }?.toByteArray()

                                val drmKidBase64 = if (drmKidBytes != null && drmKidBytes.isNotEmpty()) {
                                    Base64.encodeToString(
                                        drmKidBytes,
                                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                    )
                                } else {
                                    null
                                }

                                val drmKeyBase64 = if (drmKeyBytes != null && drmKeyBytes.isNotEmpty()) {
                                    Base64.encodeToString(
                                        drmKeyBytes,
                                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                    )
                                } else {
                                    null
                                }

                                if (drmKeyBase64 != null) bufferedKey = drmKeyBase64
                                if (drmKidBase64 != null) bufferedKeyId = drmKidBase64
                            }
                        }
                    }
                    !line.startsWith("#") -> {
                        // URL line - now create the PlaylistItem with all buffered properties
                        // Handle multi-line URLs by accumulating lines until we find a complete URL or hit a comment
                        var fullLine = line
                        var j = i + 1

                        // Continue reading lines until we find a line that starts with # or we reach end of file
                        while (j < allLines.size &&
                            !allLines[j].trim().startsWith("#") &&
                            allLines[j].trim().isNotEmpty()) {
                            fullLine += allLines[j].trim()
                            j++
                        }

                        // Update index to skip the lines we've already processed
                        i = j - 1

                        // Parse URL and its pipe-separated parameters
                        val url = fullLine.getUrl()
                        val urlUserAgent = fullLine.getUrlParameter("user-agent")
                        val urlReferrer = fullLine.getUrlParameter("referer")
                        val urlReferrerAlias = fullLine.getUrlParameter("referrer")
                        val urlCookie = fullLine.getUrlParameter("cookie")
                        val urlOrigin = fullLine.getUrlParameter("origin")
                        val urlKey = fullLine.getUrlParameter("key")
                        val urlKeyid = fullLine.getUrlParameter("keyid")
                        val urlLicenseUrl = fullLine.getUrlParameter("licenseUrl")

                        // Build final headers - URL params override buffered values
                        var finalHeaders = bufferedHeaders
                        val resolvedReferrer = urlReferrer ?: urlReferrerAlias
                        if (resolvedReferrer != null) {
                            finalHeaders = finalHeaders + mapOf("Referer" to resolvedReferrer)
                        }
                        if (urlOrigin != null) {
                            finalHeaders = finalHeaders + mapOf("Origin" to urlOrigin)
                        }

                        // Create the PlaylistItem - URL params take priority over buffered values
                        val item = PlaylistItem(
                            title = bufferedTitle ?: "Unknown Channel",
                            attributes = bufferedAttributes,
                            url = url,
                            headers = finalHeaders,
                            userAgent = urlUserAgent ?: bufferedUserAgent,
                            cookie = urlCookie ?: bufferedCookie,
                            key = urlKey ?: bufferedKey,
                            keyid = urlKeyid ?: bufferedKeyId,
                            licenseUrl = urlLicenseUrl ?: bufferedLicenseUrl,
                            drmKeys = bufferedDrmKeys
                        )

                        playlistItems.add(item)

                        // Reset all buffers for next item
                        bufferedCookie = null
                        bufferedUserAgent = null
                        bufferedHeaders = emptyMap()
                        bufferedKey = null
                        bufferedKeyId = null
                        bufferedLicenseUrl = null
                        bufferedDrmKeys = emptyMap()
                        bufferedTitle = null
                        bufferedAttributes = emptyMap()
                    }
                }
            }
            i++
        }
        return Playlist(playlistItems)
    }

    /**
     * Replace "" (quotes) from given string.
     */
    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    /**
     * Get title of media.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     * Result: Title
     */
    private fun String.getTitle(): String? {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val afterExtInf = replace(extInfRegex, "").trim()

        // Find the last comma that's not inside quotes
        var lastCommaIndex = -1
        var insideQuotes = false

        for (i in afterExtInf.indices) {
            when (afterExtInf[i]) {
                '"' -> insideQuotes = !insideQuotes
                ',' -> if (!insideQuotes) lastCommaIndex = i
            }
        }

        return if (lastCommaIndex != -1 && lastCommaIndex < afterExtInf.length - 1) {
            afterExtInf.substring(lastCommaIndex + 1).trim().replaceQuotesAndTrim()
        } else {
            // Fallback to original logic if no comma found
            afterExtInf.split(",").lastOrNull()?.replaceQuotesAndTrim()
        }
    }

    /**
     * Get media url.
     *
     * Example:-
     *
     * Input:
     * ```
     * https://example.com/sample.m3u8|user-agent="Custom"
     * ```
     * Result: https://example.com/sample.m3u8
     */
    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    private fun String.getUrlParameter(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()

        // Split by & to get individual parameters
        val params = paramsString.split("&")

        for (param in params) {
            val keyValuePair = param.split("=", limit = 2)
            if (keyValuePair.size == 2) {
                val paramKey = keyValuePair[0].trim()
                val paramValue = keyValuePair[1].trim()
                if (paramKey.equals(key, ignoreCase = true)) {
                    return paramValue.replaceQuotesAndTrim()
                }
            }
        }

        return null
    }

    /**
     * Get attributes from `#EXTINF` tag as Map<String, String>.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     * Result will be equivalent to kotlin map:
     * ```Kotlin
     * mapOf(
     *   "tvg-id" to "1234",
     *   "group-title" to "Kids",
     *   "tvg-logo" to "url/to/logo"
     * )
     * ```
     */
    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val afterExtInf = replace(extInfRegex, "").trim()

        // Find the last comma that's not inside quotes to separate title from attributes
        var lastCommaIndex = -1
        var insideQuotes = false

        for (i in afterExtInf.indices) {
            when (afterExtInf[i]) {
                '"' -> insideQuotes = !insideQuotes
                ',' -> if (!insideQuotes) lastCommaIndex = i
            }
        }

        val attributesString = if (lastCommaIndex != -1) {
            afterExtInf.substring(0, lastCommaIndex).trim()
        } else {
            afterExtInf.trim()
        }

        val attributes = mutableMapOf<String, String>()

        // Use regex to match key="value" or key=value patterns
        val attributeRegex = Regex("""(\w[-\w]*)\s*=\s*(?:"([^"]*)"|([^\s,]+))""", RegexOption.IGNORE_CASE)

        attributeRegex.findAll(attributesString).forEach { matchResult ->
            val key = matchResult.groups[1]?.value ?: ""
            val quotedValue = matchResult.groups[2]?.value
            val unquotedValue = matchResult.groups[3]?.value
            val value = quotedValue ?: unquotedValue ?: ""

            if (key.isNotEmpty()) {
                attributes[key] = value.trim()
            }
        }

        return attributes
    }

    /**
     * Get value from a tag.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTVLCOPT:http-referrer=http://example.com/
     * ```
     * Result: http://example.com/
     */
    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }
}
