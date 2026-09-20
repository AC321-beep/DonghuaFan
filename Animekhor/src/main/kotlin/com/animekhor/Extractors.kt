package com.animekhor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ---------- File-level constants (compiled once per process) ----------
private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

private val RE_PACKED_SCRIPT = Regex(
    """eval\(\s*function\s*\(p,a,c,k,e,[a-zA-Z0-9_]\).*?split\('\|'\).*?\)"""
)
private val RE_M3U8_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ANY = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
private val RE_MP4_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_MP4_ANY = Regex("""(https?://[^"']+\.(?:mp4|mkv)[^"']*)""")

private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
) {
    // Only copy when we actually need to add the UA
    val safeHeaders: Map<String, String> =
        if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val response = try { app.get(url, headers = safeHeaders).text } catch (e: Exception) { return }

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response

    val m3u8 = RE_M3U8_ATTR.find(unpacked)?.groupValues?.get(1)
        ?: RE_M3U8_ANY.find(unpacked)?.groupValues?.get(1)

    if (m3u8 != null) {
        M3u8Helper.generateM3u8(name, m3u8.replace("\\/", "/"), url, headers = safeHeaders)
            .forEach(callback)
    } else {
        val mp4 = RE_MP4_ATTR.find(unpacked)?.groupValues?.get(1)
            ?: RE_MP4_ANY.find(unpacked)?.groupValues?.get(1)
        if (mp4 != null) {
            callback.invoke(
                newExtractorLink(name = name, source = name, url = mp4.replace("\\/", "/"), type = INFER_TYPE) {
                    this.referer = url
                }
            )
        }
    }
}

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID_PARAM = Regex("""[?&]mid=(\d+)""")

        // Static quality map — no `when` chain rebuilt per video
        private val QUALITY_MAP = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low"    to Qualities.P360.value,
            "sd"     to Qualities.P480.value,
            "hd"     to Qualities.P720.value,
            "full"   to Qualities.P1080.value,
            "quad"   to Qualities.P1440.value,
            "ultra"  to Qualities.P2160.value,
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID_PARAM.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank() || !id.all { it.isDigit() }) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val json = app.post(apiUrl).parsedSafe<OkRuResponse>() ?: return

            // 1. MP4 first
            json.videos?.forEach { video ->
                val qName = video.name?.lowercase().orEmpty()
                val vidUrl = video.url.orEmpty()
                if (vidUrl.isBlank() || vidUrl.contains("usr_login")) return@forEach

                val qualityValue = QUALITY_MAP[qName] ?: Qualities.Unknown.value
                val displayLabel = if (qualityValue != Qualities.Unknown.value) "MP4 ${qualityValue}p" else "MP4 $qName"

                callback(
                    newExtractorLink(
                        name = "${this.name} MP4",
                        source = "${this.name} $displayLabel",
                        url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }

            // 2. HLS → 3. DASH fallback
            val hlsUrl = json.hlsManifestUrl.orEmpty()
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8(
                    "$name HLS",
                    hlsUrl.replace("\\u0026", "&").replace("\\/", "/"),
                    url
                ).forEach(callback)
            } else {
                val dashUrl = json.dashManifestUrl.orEmpty()
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(
                        newExtractorLink(
                            name = "$name DASH",
                            source = "$name DASH",
                            url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"),
                            type = ExtractorLinkType.DASH
                        ) { this.referer = "https://ok.ru/" }
                    )
                }
            }
        } catch (e: Exception) {
            // Fails silently — same as original
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuResponse(
        val videos: List<OkRuVideo>? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuVideo(
        val name: String? = null,
        val url: String? = null,
    )
}

class P2pstream : ExtractorApi() {
    override var name = "P2pstream"
    override var mainUrl = "https://animekhor.p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private val HEADERS = mapOf(
            "Origin" to "https://animekhor.p2pstream.vip",
            "Referer" to "https://animekhor.p2pstream.vip/"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        manualJsUnpackExtraction(fixedUrl, name, HEADERS, callback)
    }
}

class UpnsLive : ExtractorApi() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
    override val requiresReferer = true

    companion object {
        private val HEADERS = mapOf(
            "Origin" to "https://animekhor.upns.live",
            "Referer" to "https://animekhor.upns.live/"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        manualJsUnpackExtraction(fixedUrl, name, HEADERS, callback)
    }
}

class Bysekoze : ExtractorApi() {
    override var name = "VGPlayer"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private val HEADERS = mapOf(
            "Origin" to "https://bysekoze.com",
            "Referer" to "https://bysekoze.com/"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        manualJsUnpackExtraction(url, name, HEADERS, callback)
    }
}

class Emturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    companion object {
        private val STATIC_HEADERS = mapOf(
            "Origin" to "https://emturbovid.com",
            "Accept" to "*/*"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Referer depends on the input URL, so build it inline — only 1 allocation, cheap
        val headers = STATIC_HEADERS + ("Referer" to url)
        manualJsUnpackExtraction(url, name, headers, callback)
    }
}

class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private val RE_VIDEO_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")

        private val JUNK = listOf("/assets/", "loop", "preview", "tracker")
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Fast path — already a direct file
        if (url.endsWith(".mp4") || url.endsWith(".m3u8")) {
            callback(newExtractorLink(name, name, url, INFER_TYPE) { this.referer = referer ?: mainUrl })
            return
        }

        val html = try { app.get(url, referer = referer ?: mainUrl).text } catch (e: Exception) { return }

        // LinkedHashSet → insertion order preserved → highest quality first
        val scrapedUrls = LinkedHashSet<String>()

        RE_VIDEO_URL.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            if (JUNK.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    val precedingText = html.substring(Math.max(0, match.range.first - 150), match.range.first)

                    val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                        ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

                    val qualityInt = qMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

                    callback(
                        newExtractorLink(
                            name = name,
                            source = if (qualityInt != Qualities.Unknown.value) "$name ${qualityInt}p" else name,
                            url = cleanUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }
}

class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )
        private val JSON_HEADERS = HEADERS + ("Content-Type" to "application/json")

        private val RE_DATAS = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = try { app.get(url, headers = HEADERS).document } catch (e: Exception) { return }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)

        val responseText = try {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = JSON_HEADERS,
                requestBody = reqBody
            ).text
        } catch (e: Exception) { return }

        // NOTE: sources[] is polymorphic (objects OR bare strings).
        // A data class + parsedSafe would break on the string entries,
        // so org.json is kept here intentionally.
        val json = JSONObject(responseText).optJSONObject("result") ?: return
        val sources = json.optJSONArray("sources") ?: return

        val dedupSources = HashSet<String>(sources.length())

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)
            if (src != null) {
                if (src.optBoolean("status", false) || src.has("url")) {
                    val srcUrl = src.optString("url")
                    if (srcUrl.isNotBlank() && dedupSources.add(srcUrl)) {

                        val rawLabel = src.optString("label")
                            .ifBlank { src.optString("quality") }
                            .ifBlank { src.optString("type") }
                            .ifBlank { src.optString("res") }
                            .ifBlank { src.optString("resolution") }

                        val qualityInt = when {
                            rawLabel.contains("1080") || rawLabel.contains("FHD", true) -> Qualities.P1080.value
                            rawLabel.contains("720") || rawLabel.contains("HD", true) -> Qualities.P720.value
                            rawLabel.contains("480") || rawLabel.contains("SD", true) -> Qualities.P480.value
                            rawLabel.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        val displayString = when {
                            qualityInt != Qualities.Unknown.value -> "${qualityInt}p"
                            rawLabel.isNotBlank() -> rawLabel.uppercase()
                            else -> ""
                        }

                        if (srcUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8("$name HLS", srcUrl, mainUrl).forEach { link ->
                                val finalQuality =
                                    if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value)
                                        qualityInt else link.quality

                                val finalName = when {
                                    finalQuality == qualityInt && qualityInt != Qualities.Unknown.value ->
                                        "$name HLS ${qualityInt}p"
                                    link.name == "$name HLS" && displayString.isNotBlank() ->
                                        "$name HLS $displayString"
                                    else -> link.name
                                }

                                callback(
                                    ExtractorLink(
                                        name = link.name,
                                        source = finalName,
                                        url = link.url,
                                        referer = link.referer,
                                        quality = finalQuality,
                                        type = link.type,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                )
                            }
                        } else {
                            val sourceName = if (displayString.isNotBlank()) "$name $displayString" else name
                            callback(
                                newExtractorLink(name = name, source = sourceName, url = srcUrl, type = INFER_TYPE) {
                                    this.referer = mainUrl
                                    this.quality = qualityInt
                                }
                            )
                        }
                    }
                }
            } else {
                val stringUrl = sources.optString(i)
                if (stringUrl.isNotBlank() && dedupSources.add(stringUrl)) {
                    if (stringUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8("$name HLS", stringUrl, mainUrl).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(name = name, source = name, url = stringUrl, type = INFER_TYPE) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            }
        }
    }
}
