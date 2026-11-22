package com.Animexin

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FileMoonSx : Filesim() {
    override val name = "FileMoonSx"
    override val mainUrl = "https://filemoon.sx"
}

class Waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class Wishfast : StreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://wishfast.top"
}

class Vtbe : ExtractorApi() {
    override val name = "Vtbe"
    override val mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(url, referer = mainUrl).document
            val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
            val unpacked = JsUnpacker(script).unpack() ?: return null
            val link = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](.*?)['"]""")
                .find(unpacked)?.groupValues?.get(1) ?: return null

            return listOf(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    referer = referer ?: mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        } catch (e: Exception) {
            AnimexinLogger.e("Vtbe", "Extraction failed for $url", e)
            return null
        }
    }
}

class OkRu : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return null
            AnimexinLogger.d("OkRu") { "Fetching metadata for mid=$id" }

            val jsonStr = app.post("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id").text
            if (!jsonStr.startsWith("{")) return null
            val json = JSONObject(jsonStr)

            val links = mutableListOf<ExtractorLink>()
            val videos = json.optJSONArray("videos")

            if (videos != null && videos.length() > 0) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    val qName = video.optString("name").lowercase()
                    val vidUrl = video.optString("url")

                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue

                    val qualityValue = when (qName) {
                        "mobile" -> Qualities.P144.value
                        "lowest" -> Qualities.P240.value
                        "low"    -> Qualities.P360.value
                        "sd"     -> Qualities.P480.value
                        "hd"     -> Qualities.P720.value
                        "full"   -> Qualities.P1080.value
                        "quad"   -> Qualities.P1440.value
                        "ultra"  -> Qualities.P2160.value
                        else     -> Qualities.Unknown.value
                    }

                    links.add(
                        newExtractorLink(
                            name = "${this.name} MP4",
                            source = "${this.name} MP4",
                            url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                            type = INFER_TYPE
                        ) {
                            this.referer = "https://ok.ru/"
                            this.quality = qualityValue
                        }
                    )
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                links.addAll(
                    M3u8Helper.generateM3u8(
                        "$name HLS",
                        hlsUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        url
                    )
                )
            }

            AnimexinLogger.d("OkRu") { "mid=$id resolved ${links.size} link(s)" }
            return links
        } catch (e: Exception) {
            AnimexinLogger.e("OkRu", "Extraction failed for $url", e)
            return null
        }
    }
}

class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            var videoId = Regex(
                """([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""",
                RegexOption.IGNORE_CASE
            ).find(url)?.groupValues?.get(1)

            val shortId = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

            val lookupId = shortId ?: videoId
            AnimexinLogger.d("DTube") { "Lookup id=$lookupId (uuid=$videoId)" }

            if (lookupId != null) {
                try {
                    val apiResponse = app.get("https://api.d.tube/videos/$lookupId").text
                    if (apiResponse.startsWith("{")) {
                        val json = JSONObject(apiResponse)
                        videoId = json.optString("_id").takeIf { it.isNotBlank() }
                            ?: json.optString("id").takeIf { it.isNotBlank() }
                            ?: json.optString("uuid").takeIf { it.isNotBlank() }
                            ?: videoId

                        val directHls = json.optString("hlsUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("manifestUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("gatewayUrl").takeIf { it.isNotBlank() }

                        if (!directHls.isNullOrBlank()) {
                            AnimexinLogger.d("DTube") { "API returned direct HLS: $directHls" }
                            return M3u8Helper.generateM3u8(name, directHls, url)
                        }
                    }
                } catch (e: Exception) {
                    AnimexinLogger.d("DTube") { "API lookup failed, probing NAS nodes" }
                }
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    try {
                        if (app.get(m3u8Url).isSuccessful) {
                            AnimexinLogger.d("DTube") { "Found active stream on node $node: $m3u8Url" }
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    } catch (e: Exception) {
                        // Try next node
                    }
                }
            }
        } catch (e: Exception) {
            AnimexinLogger.e("DTube", "Extraction failed for $url", e)
        }
        return null
    }
}

class Odysee : ExtractorApi() {
    override var name = "Odysee"
    override var mainUrl = "https://odysee.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 4_000L
        private const val LBRY_PROXY_API = "https://api.na-backend.odysee.com/api/v1/proxy?m=get"

        private val RE_CONTENT_URL = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
        private val RE_STREAM_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*""",
            RegexOption.IGNORE_CASE
        )
        private val RE_ODYCDN_STREAM = Regex(
            """https?:(?:\\/|/)(?:\\/|/)player\.odycdn\.com/[^"'\s<>]+""",
            RegexOption.IGNORE_CASE
        )

        private fun buildLbryUri(url: String): String? {
            val clean = url.substringBefore('?').substringBefore('#')
            val rawPath = when {
                "/$/embed/" in clean -> clean.substringAfter("/$/embed/")
                "odysee.com/" in clean -> clean.substringAfter("odysee.com/")
                else -> return null
            }
            val decodedPath = runCatching { URLDecoder.decode(rawPath, "UTF-8") }
                .getOrDefault(rawPath)
                .trim('/')
            if (decodedPath.isBlank()) return null

            val lbryPath = decodedPath.replace(':', '#')
            return if (lbryPath.startsWith("lbry://")) lbryPath else "lbry://$lbryPath"
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "$mainUrl/"
        AnimexinLogger.d("Odysee") { "Fetching embed page: $url" }

        // 1. Scrape the embed page directly (preserves ?r=...&signature=...&signature_ts=... for unlisted streams)
        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching { app.get(url, referer = pageReferer).text }.getOrNull()
        }

        var streamUrl: String? = null
        if (!html.isNullOrBlank()) {
            streamUrl = RE_CONTENT_URL.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: RE_STREAM_URL.find(html)?.value?.replace("\\/", "/")
                ?: RE_ODYCDN_STREAM.find(html)?.value?.replace("\\/", "/")
            if (!streamUrl.isNullOrBlank()) {
                AnimexinLogger.d("Odysee") { "Scraped stream URL from HTML: $streamUrl" }
            }
        }

        // 2. Fallback to LBRY/Odysee JSON-RPC proxy if the embed HTML didn't inline the stream URL
        if (streamUrl.isNullOrBlank()) {
            val lbryUri = buildLbryUri(url)
            AnimexinLogger.d("Odysee") { "HTML scrape empty; querying LBRY API for uri=$lbryUri" }
            if (!lbryUri.isNullOrBlank()) {
                val apiResp = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    runCatching {
                        app.post(
                            LBRY_PROXY_API,
                            headers = mapOf("Content-Type" to "application/json"),
                            json = mapOf(
                                "jsonrpc" to "2.0",
                                "method" to "get",
                                "params" to mapOf("uri" to lbryUri)
                            )
                        ).text
                    }.getOrNull()
                }

                if (!apiResp.isNullOrBlank() && apiResp.startsWith("{")) {
                    streamUrl = runCatching {
                        JSONObject(apiResp)
                            .optJSONObject("result")
                            ?.optString("streaming_url")
                            ?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                    if (!streamUrl.isNullOrBlank()) {
                        AnimexinLogger.d("Odysee") { "LBRY API returned streaming_url: $streamUrl" }
                    }
                }
            }
        }

        val resolved = streamUrl?.takeIf { it.isNotBlank() }
        if (resolved == null) {
            AnimexinLogger.d("Odysee") { "Failed to resolve any stream for: $url" }
            return
        }

        val isHls = resolved.contains(".m3u8", ignoreCase = true) || resolved.contains("/hls/", ignoreCase = true)

        if (isHls) {
            callback(
                newExtractorLink(name, "$name (Auto)", resolved, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            val variants = withTimeoutOrNull(SPLIT_TIMEOUT_MS) {
                runCatching { M3u8Helper.generateM3u8(name, resolved, url) }.getOrNull()
            }
            variants?.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, resolved, INFER_TYPE) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

class MegaNz : ExtractorApi() {
    override var name = "Mega"
    override var mainUrl = "https://mega.nz"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val PROXY_IDLE_TIMEOUT_MS = 20 * 60 * 1000
        private const val BUFFER_SIZE = 65_536

        private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val RE_EMBED_OR_FILE = Regex(
            """mega\.(?:co\.)?nz/(?:embed|file)/([a-zA-Z0-9_-]+)#([a-zA-Z0-9_-]+)"""
        )
        private val RE_LEGACY_HASH = Regex(
            """mega\.(?:co\.)?nz/#!([a-zA-Z0-9_-]+)!([a-zA-Z0-9_-]+)"""
        )

        private fun decodeMegaKey(encodedKey: String): Pair<ByteArray, ByteArray>? {
            return try {
                val padded = when (encodedKey.length % 4) {
                    2 -> "$encodedKey=="
                    3 -> "$encodedKey="
                    else -> encodedKey
                }
                val raw = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
                if (raw.size < 32) return null

                val aesKey = ByteArray(16)
                for (i in 0 until 16) {
                    aesKey[i] = (raw[i].toInt() xor raw[i + 16].toInt()).toByte()
                }
                val nonce = ByteArray(8)
                System.arraycopy(raw, 16, nonce, 0, 8)
                Pair(aesKey, nonce)
            } catch (e: Exception) {
                null
            }
        }

        private fun startLocalDecryptProxy(
            upstreamUrl: String,
            fileSize: Long,
            aesKey: ByteArray,
            nonce: ByteArray,
            fileId: String
        ): String {
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = PROXY_IDLE_TIMEOUT_MS
            }
            val port = server.localPort

            proxyScope.launch {
                try {
                    while (!server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: SocketTimeoutException) {
                            break
                        } catch (e: Exception) {
                            break
                        }
                        launch {
                            handleClientRequest(client, upstreamUrl, fileSize, aesKey, nonce)
                        }
                    }
                } finally {
                    runCatching { server.close() }
                }
            }

            return "http://127.0.0.1:$port/mega_$fileId.mp4"
        }

        private fun handleClientRequest(
            client: Socket,
            upstreamUrl: String,
            fileSize: Long,
            aesKey: ByteArray,
            nonce: ByteArray
        ) {
            client.use { socket ->
                var upstreamConn: HttpURLConnection? = null
                try {
                    socket.soTimeout = 30_000
                    val input = BufferedInputStream(socket.getInputStream(), 8192)
                    val out = socket.getOutputStream()

                    val requestLines = mutableListOf<String>()
                    val lineBuf = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b == -1) break
                        if (b == '\n'.code) {
                            val line = lineBuf.toString().trimEnd('\r')
                            lineBuf.clear()
                            if (line.isEmpty()) break
                            requestLines.add(line)
                        } else {
                            lineBuf.append(b.toChar())
                        }
                    }
                    if (requestLines.isEmpty()) return

                    val method = requestLines[0].substringBefore(' ').uppercase()
                    val rangeHeader = requestLines.firstOrNull {
                        it.startsWith("Range:", ignoreCase = true)
                    }?.substringAfter(':')?.trim()

                    var start = 0L
                    var end = fileSize - 1L
                    var hasRange = false

                    if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                        val spec = rangeHeader.substringAfter('=').substringBefore(',').trim()
                        val startStr = spec.substringBefore('-')
                        val endStr = spec.substringAfter('-', "")
                        start = startStr.toLongOrNull() ?: 0L
                        if (endStr.isNotBlank()) {
                            end = minOf(endStr.toLongOrNull() ?: (fileSize - 1L), fileSize - 1L)
                        }
                        hasRange = true
                    }

                    if (start < 0L || start > end || start >= fileSize) {
                        val err = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileSize\r\nConnection: close\r\n\r\n"
                        out.write(err.toByteArray(Charsets.UTF_8))
                        out.flush()
                        return
                    }

                    val contentLength = end - start + 1L
                    val blockIndex = start / 16L
                    val alignedStart = blockIndex * 16L
                    val skipBytes = (start - alignedStart).toInt()

                    val iv = ByteArray(16)
                    System.arraycopy(nonce, 0, iv, 0, 8)
                    var ctr = blockIndex
                    for (i in 15 downTo 8) {
                        iv[i] = (ctr and 0xFFL).toByte()
                        ctr = ctr ushr 8
                    }

                    val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                    }

                    upstreamConn = (URL(upstreamUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12_000
                        readTimeout = 30_000
                        setRequestProperty("Range", "bytes=$alignedStart-$end")
                    }

                    val respCode = upstreamConn.responseCode
                    if (respCode !in 200..299) {
                        AnimexinLogger.e("Mega", "Upstream storage returned HTTP $respCode")
                        val err = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(err.toByteArray(Charsets.UTF_8))
                        out.flush()
                        return
                    }

                    val headers = buildString {
                        if (hasRange) {
                            append("HTTP/1.1 206 Partial Content\r\n")
                            append("Content-Range: bytes $start-$end/$fileSize\r\n")
                        } else {
                            append("HTTP/1.1 200 OK\r\n")
                        }
                        append("Content-Type: video/mp4\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Content-Length: $contentLength\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(headers.toByteArray(Charsets.UTF_8))
                    out.flush()

                    if (method == "HEAD") return

                    CipherInputStream(BufferedInputStream(upstreamConn.inputStream, BUFFER_SIZE), cipher).use { cipherIn ->
                        var toSkip = skipBytes
                        while (toSkip > 0) {
                            val skipped = cipherIn.skip(toSkip.toLong()).toInt()
                            if (skipped <= 0) {
                                if (cipherIn.read() == -1) return
                                toSkip--
                            } else {
                                toSkip -= skipped
                            }
                        }

                        val buffer = ByteArray(BUFFER_SIZE)
                        var remaining = contentLength
                        while (remaining > 0L) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = cipherIn.read(buffer, 0, toRead)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                        out.flush()
                    }
                } catch (e: Exception) {
                    // Client disconnected or seeked
                } finally {
                    runCatching { upstreamConn?.disconnect() }
                }
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val match = RE_EMBED_OR_FILE.find(url) ?: RE_LEGACY_HASH.find(url)
        if (match == null) {
            AnimexinLogger.d("Mega") { "URL did not match expected Mega pattern: $url" }
            return
        }
        val fileId = match.groupValues[1]
        val rawKeyStr = match.groupValues[2]

        val (aesKey, nonce) = decodeMegaKey(rawKeyStr) ?: run {
            AnimexinLogger.e("Mega", "Failed to decode AES key fragment for fileId=$fileId")
            return
        }

        AnimexinLogger.d("Mega") { "Querying Mega API for fileId=$fileId" }
        val apiResponse = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching {
                app.post(
                    "https://g.api.mega.co.nz/cs?id=0",
                    headers = mapOf("Content-Type" to "application/json"),
                    json = listOf(
                        mapOf(
                            "a" to "g",
                            "g" to 1,
                            "p" to fileId
                        )
                    )
                ).text
            }.getOrNull()
        } ?: return

        if (!apiResponse.startsWith("[")) {
            AnimexinLogger.e("Mega", "Unexpected Mega API response for $fileId: ${apiResponse.take(120)}")
            return
        }

        val fileObj = runCatching { JSONArray(apiResponse).optJSONObject(0) }.getOrNull() ?: return
        val fileSize = fileObj.optLong("s", -1L)
        val downloadUrl = fileObj.optString("g")

        if (fileSize <= 0L || downloadUrl.isBlank()) {
            AnimexinLogger.e("Mega", "Missing storage URL or size in Mega response for $fileId")
            return
        }

        val localProxyUrl = startLocalDecryptProxy(
            upstreamUrl = downloadUrl,
            fileSize = fileSize,
            aesKey = aesKey,
            nonce = nonce,
            fileId = fileId
        )
        AnimexinLogger.d("Mega") { "Started local decrypt proxy ($fileSize bytes) -> $localProxyUrl" }

        callback(
            newExtractorLink(name, name, localProxyUrl, ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 5_000L

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private val RE_VIDEO_URL = Regex(
            """(?:https?:)?(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")
        private val RE_JSON_CONFIG =
            Regex("""Rumble\(\s*['"]play['"]\s*,\s*\{(.*?)\}\s*\)""", RegexOption.DOT_MATCHES_ALL)
        private val RE_VIDEO_ID_FALLBACK = Regex(
            """['"]video['"]\s*:\s*(?:\{[^}]*?['"]id['"]\s*:\s*['"]([a-zA-Z0-9_-]+)['"]|['"]([a-zA-Z0-9_-]+)['"])"""
        )
        private val RE_EMBED_PATH_ID = Regex("""/embed/([a-zA-Z0-9_-]+)""")

        private val JUNK_KEYWORDS =
            listOf("/assets/", "loop", "preview", "tracker", "thumb", "timeline", ".faa.mp4", ".tar")
        private val HARD_JUNK =
            listOf("/assets/", "tracker", "thumb", "timeline", ".faa.mp4", ".tar")

        private fun normalizeStreamUrl(raw: String): String {
            val unescaped = raw.replace("\\/", "/")
            return if (unescaped.startsWith("//")) "https:$unescaped" else unescaped
        }

        private fun isRumbleHost(url: String): Boolean {
            val host = url.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .lowercase()
            if (host.startsWith("a.ads.") || host.startsWith("ads.")) return false
            return host == "rumble.com" || host.endsWith(".rumble.com") ||
                host == "rumble.cloud" || host.endsWith(".rumble.cloud") ||
                host == "1a-1791.com" || host.endsWith(".1a-1791.com") ||
                host == "rmbl.ws" || host.endsWith(".rmbl.ws")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanPath = url.substringBefore('?').substringBefore('#')
        if (cleanPath.endsWith(".mp4", ignoreCase = true) || cleanPath.endsWith(".m3u8", ignoreCase = true)) {
            AnimexinLogger.d("Rumble") { "Fast-path direct stream: $url" }
            val linkType = if (cleanPath.endsWith(".m3u8", ignoreCase = true))
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, linkType) { this.referer = referer ?: url })
            return
        }

        AnimexinLogger.d("Rumble") { "Fetching page: $url" }
        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try { app.get(url, referer = referer ?: mainUrl, headers = DEFAULT_HEADERS).text }
            catch (e: Exception) { null }
        } ?: run {
            AnimexinLogger.e("Rumble", "Failed or timed out fetching: $url")
            return
        }

        // 1. Inline HTML payload path
        val inlineSlice = when {
            html.contains("p.f[") -> html.substringAfter("p.f[").substringBefore("\"evt\":{")
            html.contains("{\"mp4") -> html.substringAfter("{\"mp4").substringBefore("\"evt\":{")
            else -> ""
        }
        if (inlineSlice.isNotEmpty() && extractFromScriptData(inlineSlice, url, "InlineHTML", callback)) {
            return
        }

        // 2. API path
        val configMatch = RE_JSON_CONFIG.find(html)
        val rawConfig = configMatch?.groupValues?.get(1)
        val decodedHtml = runCatching { URLDecoder.decode(html, "UTF-8") }.getOrDefault(html)

        val videoId = if (rawConfig != null) {
            try {
                val jsonObj = JSONObject("{$rawConfig}")
                jsonObj.optJSONObject("video")?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: (jsonObj.opt("video") as? String)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            } ?: RE_VIDEO_ID_FALLBACK.find(rawConfig)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2] }
            }?.takeIf { it.isNotBlank() }
        } else {
            null
        } ?: RE_EMBED_PATH_ID.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
          ?: RE_EMBED_PATH_ID.find(decodedHtml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        AnimexinLogger.d("Rumble") { "Inline scrape missed; resolved embed videoId=$videoId" }

        if (!videoId.isNullOrBlank()) {
            val apiUrls = listOf(
                "https://rumble.com/embedJS/u4/?ifr=0&dref=&request=video&ver=2&v=$videoId",
                "https://rumble.com/embedJS/u3/?request=video&v=$videoId"
            )
            for (apiUrl in apiUrls) {
                val apiResponse = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    runCatching {
                        app.get(apiUrl, referer = url, headers = DEFAULT_HEADERS).text
                    }.getOrNull()
                } ?: continue
                if (extractFromScriptData(apiResponse, url, apiUrl.substringAfter("embedJS/").substringBefore('/'), callback)) return
            }
        }

        // 3. Full HTML scrape fallback
        extractFromScriptData(html, url, "FullHTML", callback)
    }

    private suspend fun extractFromScriptData(
        scriptData: String,
        url: String,
        stage: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val scrapedUrls = LinkedHashSet<String>()
        var emitted = false

        // PASS 1 — strict (verified Rumble CDN hosts only, full junk filter)
        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val cleanUrl = normalizeStreamUrl(match.value)

            if (!isRumbleHost(cleanUrl)) return@forEach
            if (JUNK_KEYWORDS.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                emitted = true
                AnimexinLogger.d("Rumble") { "[$stage/Pass1] Matched stream: $cleanUrl" }
                emitOne(cleanUrl, match, scriptData, url, name, callback)
            }
        }

        // PASS 2 — trailer fallback (only if strict pass emitted nothing)
        if (!emitted) {
            RE_VIDEO_URL.findAll(scriptData).forEach { match ->
                val cleanUrl = normalizeStreamUrl(match.value)

                if (HARD_JUNK.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

                if (scrapedUrls.add(cleanUrl)) {
                    emitted = true
                    AnimexinLogger.d("Rumble") { "[$stage/Pass2-Trailer] Matched stream: $cleanUrl" }
                    emitOne(cleanUrl, match, scriptData, url, "$name Trailer", callback)
                }
            }
        }

        return emitted
    }

    private suspend fun emitOne(
        cleanUrl: String,
        match: MatchResult,
        scriptData: String,
        referer: String,
        labelBase: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            callback(
                newExtractorLink(name, "$labelBase (Auto)", cleanUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = DEFAULT_HEADERS
                }
            )

            val variants = try {
                withTimeoutOrNull(SPLIT_TIMEOUT_MS) {
                    M3u8Helper.generateM3u8(labelBase, cleanUrl, referer, headers = DEFAULT_HEADERS)
                }
            } catch (e: Exception) { null }
            variants?.forEach(callback)

        } else if (cleanUrl.contains(".mp4", ignoreCase = true)) {
            val precedingText = scriptData.substring(
                Math.max(0, match.range.first - 150),
                match.range.first
            )

            val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

            val displayLabel = if (qMatch != null)
                "$labelBase ${qMatch.groupValues[1]}p"
            else
                labelBase

            callback(
                newExtractorLink(name, displayLabel, cleanUrl, INFER_TYPE) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = DEFAULT_HEADERS
                }
            )
        }
    }
}
