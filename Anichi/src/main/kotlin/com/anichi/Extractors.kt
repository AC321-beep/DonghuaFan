package com.anichi

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI

fun atob(str: String): String {
    return String(Base64.decode(str, Base64.DEFAULT), Charsets.ISO_8859_1)
}

fun decryptHex(inputStr: String): String {
    val hexString = if (inputStr.startsWith("-")) inputStr.substringAfterLast("-") else inputStr
    val sb = java.lang.StringBuilder()
    for (i in 0 until hexString.length step 2) {
        val b = hexString.substring(i, i + 2).toInt(16)
        sb.append((b xor 56).toChar())
    }
    return sb.toString()
}

fun fixUrlPath(url: String): String {
    if (url.contains(".json?")) {
        return "https://allanimenews.com" + url
    }
    return try {
        val uri = URI(url)
        val query = if (!uri.query.isNullOrEmpty()) "?" + uri.query.replace("?", "") else ""
        "https://allanimenews.com${uri.path}.json$query"
    } catch (_: Exception) {
        "https://allanimenews.com$url.json"
    }
}

suspend fun loadExtractor(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    when {
        url.contains("streamwish") || url.contains("swiftplayers") -> extractStreamWish(url, callback)
        url.contains("filemoon") -> extractFilemoon(url, callback)
        url.contains("mp4upload") -> extractMp4Upload(url, callback)
        url.contains("ok.ru") -> extractOkru(url, callback)
        url.contains("byse") || url.contains("bysekoze") -> extractByse(url, callback)
        else -> {
            com.lagradost.cloudstream3.utils.loadExtractor(url, subtitleCallback, callback)
        }
    }
}

suspend fun extractStreamWish(url: String, callback: (ExtractorLink) -> Unit) {
    val html = app.get(
        url,
        headers = mapOf("Referer" to "https://streamwish.to/", "Origin" to "https://streamwish.to")
    ).text
    val m3u8 = Regex("""file:\s*"(.*?m3u8.*?)"""").find(html)?.groupValues?.get(1) ?: return
    callback(
        ExtractorLink(
            source = "StreamWish",
            name = "StreamWish HLS",
            url = m3u8,
            referer = "https://streamwish.to/",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.HLS
        )
    )
}

suspend fun extractFilemoon(url: String, callback: (ExtractorLink) -> Unit) {
    val html = app.get(url, headers = mapOf("Referer" to url)).text
    val packed = Regex("""sources:\[\{file:"(.*?)"""").find(html)?.groupValues?.get(1) ?: return
    callback(
        ExtractorLink(
            source = "Filemoon",
            name = "Filemoon HLS",
            url = packed,
            referer = "https://filemoon.to/",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.HLS
        )
    )
}

suspend fun extractMp4Upload(url: String, callback: (ExtractorLink) -> Unit) {
    val html = app.get(url).text
    val mp4 = Regex("""src:\s*"([^"]+\.mp4[^"]*)"""").find(html)?.groupValues?.get(1) ?: return
    callback(
        ExtractorLink(
            source = "MP4Upload",
            name = "MP4Upload",
            url = mp4,
            referer = url,
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
    )
}

suspend fun extractOkru(url: String, callback: (ExtractorLink) -> Unit) {
    val html = app.get(url).text
    val dataOptions = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return
    val decoded = tryParseJson<OkRuDataOptions>(dataOptions.replace("&quot;", "\"")) ?: return
    val metadata = tryParseJson<OkRuMetadata>(decoded.flashvars?.metadata ?: "") ?: return
    val hls = metadata.hlsManifestUrl ?: return

    callback(
        ExtractorLink(
            source = "OKru",
            name = "OKru HLS",
            url = hls,
            referer = url,
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.HLS
        )
    )
}

suspend fun extractByse(url: String, callback: (ExtractorLink) -> Unit) {
    val code = url.substringAfterLast("/")
    val base = URI(url).let { "${it.scheme}://${it.host}" }
    val details = app.get("$base/api/videos/$code/embed/details").parsedSafe<ByseDetails>() ?: return
    val embedFrameUrl = details.embedFrameUrl ?: return
    val embedCode = embedFrameUrl.substringAfterLast("/")

    val playback = app.get(
        "$base/api/videos/$embedCode/embed/playback",
        headers = mapOf(
            "Referer" to embedFrameUrl,
            "X-Embed-Parent" to embedFrameUrl
        )
    ).parsedSafe<BysePlayback>()

    if (playback?.playback != null) {
        callback(
            ExtractorLink(
                source = "Byse",
                name = "Byse",
                url = url,
                referer = embedFrameUrl,
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.VIDEO
            )
        )
    }
}

// Data Transfer Objects (DTOs) used across Provider and Extractors
data class TmdbResponse(val title: String? = null, val name: String? = null)
data class SearchResponse(val data: SearchData? = null)
data class SearchData(val shows: ShowsData? = null)
data class ShowsData(val edges: List<ShowEdge>? = null)
data class ShowEdge(val _id: String? = null, val name: String? = null, val englishName: String? = null) {
    val id: String? get() = _id
}
data class EpisodeResponse(val data: EpisodeData? = null)
data class EpisodeData(val episode: EpisodeDetails? = null)
data class EpisodeDetails(val sourceUrls: List<SourceUrlItem>? = null)
data class SourceUrlItem(val sourceUrl: String? = null, val sourceName: String? = null)
data class AkDecodedPayload(val idUrl: String? = null)
data class InternalLinksResponse(val links: List<InternalLinkItem>? = null)
data class InternalLinkItem(val link: String? = null, val hls: Boolean? = null, val subtitles: List<InternalSubItem>? = null)
data class InternalSubItem(val lang: String? = null, val src: String? = null)
data class OkRuDataOptions(val flashvars: OkRuFlashVars? = null)
data class OkRuFlashVars(val metadata: String? = null)
data class OkRuMetadata(val hlsManifestUrl: String? = null)
data class ByseDetails(val embed_frame_url: String? = null) {
    val embedFrameUrl: String? get() = embed_frame_url
}
data class BysePlayback(val playback: Any? = null)
