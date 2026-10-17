package com.naruldonghua

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ── File-level constants (compiled once per process) ────────────────────────
private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

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

/**
 * Reusable JS-unpacker helper. Handles every packed-JS player (Dean Edwards
 * "p,a,c,k,e,d" encoding) which is what p2pstream, bysekoze, upns.live and
 * emturbovid all use.
 *
 * Flow:
 *   1. GET the page with the caller-supplied headers + default UA.
 *   2. If `eval(function(p,a,c,k,e,d)…)` is present, unpack it with JsUnpacker.
 *   3. Scan for m3u8 (via M3u8Helper → all qualities) OR mp4/mkv.
 *   4. Fall back to raw HTML scan when the page isn't packed.
 */
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit,
) {
    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val response = try {
        app.get(url, headers = safeHeaders).text
    } catch (_: Exception) {
        return
    }

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response

    val m3u8 = RE_M3U8_ATTR.find(unpacked)?.groupValues?.get(1)
        ?: RE_M3U8_ANY.find(unpacked)?.groupValues?.get(1)
    if (m3u8 != null) {
        M3u8Helper.generateM3u8(name, m3u8.replace("\\/", "/"), url, headers = safeHeaders)
            .forEach(callback)
        return
    }

    val mp4 = RE_MP4_ATTR.find(unpacked)?.groupValues?.get(1)
        ?: RE_MP4_ANY.find(unpacked)?.groupValues?.get(1)
    if (mp4 != null) {
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) {
            this.referer = url
        })
    }
}

/** Extract scheme + host from any URL — used so one extractor class works on all mirror domains. */
private fun originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""").find(url)?.groupValues?.getOrNull(1)

// ════════════════════════════════════════════════════════════════════════════
//  P2pstream — handles every *.p2pstream.vip subdomain.
//
//  HAR evidence:
//    AnimeKhor:    https://animekhor.p2pstream.vip/#{hash}
//    NarulDonghua: https://narulplex.p2pstream.vip/#5miiz       (play)
//                  https://narulplex.p2pstream.vip/#5miiz&dl=1 (download)
//
//  The reference did `.replace("/#", "/e/")` which passes `&dl=1` through
//  as part of the /e/ path and 404s. We strip the fragment flag first.
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "P2pstream"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return

        // FragmentBefore("&") strips the optional &dl=1 download flag.
        val id = url.substringAfter("#", "").substringBefore("&").trim()
        if (id.isBlank()) return

        val fixedUrl = "$origin/e/$id"
        val headers = mapOf(
            "Origin"  to origin,
            "Referer" to "$origin/",
        )
        manualJsUnpackExtraction(fixedUrl, name, headers, callback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Bysekoze — handles bysekoze.com AND bysejikuar.com mirror.
//
//  Both domains serve the same packed-JS player. Deriving origin from the
//  input URL means a single class works for either. HAR shows NarulDonghua
//  emits bysejikuar.com as the "FM" download server; AnimeKhor uses the
//  original bysekoze.com.
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "VGPlayer"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return
        val headers = mapOf(
            "Origin"  to origin,
            "Referer" to "$origin/",
        )
        manualJsUnpackExtraction(url, name, headers, callback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  AbyssPlayer — handles abyssplayer.com, abysplayserver.com, playhydrax.com.
//
//  All three serve the same encrypted `const datas = "…"` payload, decoded
//  via https://enc-dec.app/api/dec-abyss. HAR confirms NarulDonghua uses
//  player.abysplayserver.com as the "AB-ADS" server; AnimeKhor uses
//  abyssplayer.com / playhydrax.com.
//
//  Preserves the reference's proven playhydrax headers for the enc-dec POST —
//  the decoder service appears tuned to those headers. The initial page GET
//  uses the actual origin so the correct mirror is fetched.
// ════════════════════════════════════════════════════════════════════════════
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val RE_DATAS = Regex("""const\s+datas\s*=\s*"([^"]*)"""")

        // Proven-working headers for the enc-dec.app API call.
        private val DECODER_HEADERS = mapOf(
            "User-Agent"   to DEFAULT_UA,
            "Origin"       to "https://playhydrax.com",
            "Referer"      to "https://playhydrax.com/",
            "Content-Type" to "application/json",
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return
        val pageHeaders = mapOf(
            "User-Agent" to DEFAULT_UA,
            "Origin"     to origin,
            "Referer"    to "$origin/",
        )

        val doc = try {
            app.get(url, headers = pageHeaders).document
        } catch (_: Exception) {
            return
        }

        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = DECODER_HEADERS,
                requestBody = reqBody,
            ).text
        } catch (_: Exception) {
            return
        }

        // NOTE: sources[] is polymorphic (objects OR bare strings). A data
        // class + parsedSafe would break on the string entries, so org.json
        // is kept intentionally here.
        val json = JSONObject(responseText).optJSONObject("result") ?: return
        val sources = json.optJSONArray("sources") ?: return
        val seen = HashSet<String>(sources.length())

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)

            if (src != null) {
                if (src.optBoolean("status", false) || src.has("url")) {
                    val srcUrl = src.optString("url")
                    if (srcUrl.isBlank() || !seen.add(srcUrl)) continue

                    val rawLabel = src.optString("label")
                        .ifBlank { src.optString("quality") }
                        .ifBlank { src.optString("type") }
                        .ifBlank { src.optString("res") }
                        .ifBlank { src.optString("resolution") }

                    val qualityInt = when {
                        rawLabel.contains("1080") || rawLabel.contains("FHD", true) -> Qualities.P1080.value
                        rawLabel.contains("720")  || rawLabel.contains("HD", true)  -> Qualities.P720.value
                        rawLabel.contains("480")  || rawLabel.contains("SD", true)  -> Qualities.P480.value
                        rawLabel.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    val display = when {
                        qualityInt != Qualities.Unknown.value -> "${qualityInt}p"
                        rawLabel.isNotBlank() -> rawLabel.uppercase()
                        else -> ""
                    }

                    if (srcUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8("$name HLS", srcUrl, origin).forEach { link ->
                            val finalQ =
                                if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value)
                                    qualityInt else link.quality
                            val finalName = when {
                                finalQ == qualityInt && qualityInt != Qualities.Unknown.value ->
                                    "$name HLS ${qualityInt}p"
                                link.name == "$name HLS" && display.isNotBlank() ->
                                    "$name HLS $display"
                                else -> link.name
                            }
                            callback(ExtractorLink(
                                name = link.name,
                                source = finalName,
                                url = link.url,
                                referer = link.referer,
                                quality = finalQ,
                                type = link.type,
                                headers = link.headers,
                                extractorData = link.extractorData,
                            ))
                        }
                    } else {
                        val sourceName = if (display.isNotBlank()) "$name $display" else name
                        callback(newExtractorLink(name, sourceName, srcUrl, INFER_TYPE) {
                            this.referer = origin
                            this.quality = qualityInt
                        })
                    }
                }
            } else {
                val stringUrl = sources.optString(i)
                if (stringUrl.isNotBlank() && seen.add(stringUrl)) {
                    if (stringUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8("$name HLS", stringUrl, origin).forEach(callback)
                    } else {
                        callback(newExtractorLink(name, name, stringUrl, INFER_TYPE) {
                            this.referer = origin
                        })
                    }
                }
            }
        }
    }
}
