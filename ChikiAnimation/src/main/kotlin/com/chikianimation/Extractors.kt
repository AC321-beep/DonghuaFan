package com.chikianimation

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private const val DBG = "ChikiDbg"

private fun log(scope: String, msg: String) {
    Log.e(DBG, "[$scope] $msg")
}

private fun logEnter(scope: String, url: String, referer: String?) {
    Log.e(DBG, "════ [$scope] ▶ ENTER url='$url' referer='$referer'")
}

private fun logExit(scope: String, success: Boolean, extra: String = "") {
    val icon = if (success) "✔ EXIT-OK" else "✗ EXIT-FAIL"
    Log.e(DBG, "[$scope] $icon $extra")
}

// ---------------------------------------------------------------------------
// 1. Ghbrisk
// ---------------------------------------------------------------------------
class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

// ---------------------------------------------------------------------------
// 2. GalaxyDonghua
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 2. GalaxyDonghua — API endpoint auto-discovery
// ---------------------------------------------------------------------------
class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = GX
    override val requiresReferer = true

    companion object {
        const val GX = "https://galaxydonghua.xyz"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        // Extra candidate subdomains to try
        val ALT_HOSTS = listOf(
            "https://galaxydonghua.xyz",
            "https://api.galaxydonghua.xyz",
            "https://player.galaxydonghua.xyz",
            "https://cdn.galaxydonghua.xyz"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("GX", url, referer)

        val gxBase = embedHost(url)
        val embedId = Regex("""/embed/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
        log("GX", "gxBase='$gxBase' embedId='$embedId'")

        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to url,
            "Origin" to gxBase,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )

        // ── 1. Fetch embed page ────────────────────────────────────────────
        val page = try {
            val p = app.get(url, headers = headers).text
            log("GX", "page fetch OK, len=${p.length}")
            p
        } catch (e: Exception) {
            log("GX", "❌ page fetch failed: ${e.message}")
            logExit("GX", false, "page fetch failed")
            return
        }

        // ── 2. Decode tokens ───────────────────────────────────────────────
        val tokens = decodeGdTokens(page)
        if (tokens == null) {
            log("GX", "❌ decodeGdTokens returned null")
            logExit("GX", false, "token decode failed")
            return
        }
        log("GX", "✓ tokens decoded")

        // ── 3. Discover candidate API paths ────────────────────────────────
        val discovered = mutableSetOf<String>()

        // (a) Scan the raw HTML for /api/ and /wp-json/ paths
        scanForApiPaths(page, "page", discovered)

        // (b) Fetch and scan every external <script src> URL
        val scriptSrcs = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(page).map { it.groupValues[1] }.toList()
        log("GX", "found ${scriptSrcs.size} script src URLs")

        for (rawSrc in scriptSrcs.take(15)) {  // cap at 15 to avoid runaway
            val src = if (rawSrc.startsWith("//")) "https:$rawSrc"
                      else if (rawSrc.startsWith("/")) "$gxBase$rawSrc"
                      else rawSrc
            if (!src.startsWith("http")) continue
            if (!src.contains("galaxydonghua", true)) continue  // skip 3rd party CDNs

            try {
                val js = app.get(src, headers = headers).text
                log("GX", "  fetched js: ${src.take(80)} (${js.length} bytes)")
                scanForApiPaths(js, "js:${src.takeLast(30)}", discovered)
            } catch (e: Exception) {
                log("GX", "  ❌ js fetch failed: $src → ${e.message}")
            }
        }

        // (c) Scan the decoded JSFuck content for URLs
        //     (already done inside decodeGdTokens if you want to return it,
        //      but here we just re-parse the raw page for base64 blobs)
        Regex("""["']([A-Za-z0-9+/=_-]{200,})["']""").findAll(page).forEach { m ->
            val blob = m.groupValues[1]
            try {
                val decoded = String(Base64.decode(blob, Base64.DEFAULT))
                scanForApiPaths(decoded, "b64", discovered)
            } catch (e: Exception) { }
        }

        log("GX", "discovered ${discovered.size} unique API paths")

        // ── 4. Add fallback candidates if discovery yielded nothing ────────
        if (discovered.isEmpty()) {
            log("GX", "⚠ no paths discovered from page — using fallback list")
            listOf(
                "/api/gd/v1/config",
                "/api/gd/v2/config",
                "/api/v1/config",
                "/api/v1/player/config",
                "/api/player/config",
                "/api/embed/config",
                "/api/gd/player",
                "/api/gd/source",
                "/api/config"
            ).forEach { discovered.add(it) }
        }

        // ── 5. Try each discovered endpoint ────────────────────────────────
        var configRes: String? = null
        var usedPath: String? = null

        for (path in discovered) {
            val fullUrl = if (path.startsWith("http")) path else "$gxBase$path"

            // GET
            val getRes = try { app.get(fullUrl, headers = headers).text }
                         catch (e: Exception) { null }
            if (getRes != null && isAcceptableConfig(getRes)) {
                log("GX", "  ✓ GET $path → len=${getRes.length}")
                configRes = getRes; usedPath = path; break
            }

            // POST
            val postRes = try {
                app.post(
                    fullUrl, headers = headers,
                    data = mapOf(
                        "id" to (embedId ?: ""),
                        "code" to (embedId ?: ""),
                        "embed" to (embedId ?: "")
                    )
                ).text
            } catch (e: Exception) { null }
            if (postRes != null && isAcceptableConfig(postRes)) {
                log("GX", "  ✓ POST $path → len=${postRes.length}")
                configRes = postRes; usedPath = path; break
            }
        }

        if (configRes == null || usedPath == null) {
            log("GX", "❌ no valid config endpoint found (tried ${discovered.size})")
            logExit("GX", false, "no config endpoint")
            return
        }
        log("GX", "✓ using config endpoint '$usedPath'")

        // ── 6. Decrypt config ──────────────────────────────────────────────
        val configPlain = dcx(configRes.trim(), tokens.kaken)
            ?: dcx(configRes.trim(), tokens.apx)
            ?: run {
                log("GX", "⚠ dcx failed — using raw")
                configRes.trim()
            }
        log("GX", "configPlain len=${configPlain.length}")
        log("GX", "configPlain preview: ${configPlain.take(400)}")

        // Extract API URL template
        var apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(configPlain)?.groupValues?.get(1)
        if (apiUrlTemplate == null) {
            try {
                val cfg = JSONObject(configPlain)
                apiUrlTemplate = cfg.optString("url").takeIf { it.isNotBlank() }
                    ?: cfg.optString("api").takeIf { it.isNotBlank() }
                    ?: cfg.optString("endpoint").takeIf { it.isNotBlank() }
            } catch (e: Exception) { }
        }
        if (apiUrlTemplate == null) {
            log("GX", "❌ api url template not found in config")
            logExit("GX", false, "no api url template")
            return
        }
        log("GX", "apiUrlTemplate='$apiUrlTemplate'")

        val fixedApi = apiUrlTemplate
            .replace("{pd}", tokens.pd)
            .replace("{ps}", tokens.ps)
            .replace("{qsx}", tokens.qsx)
            .replace("{kaken}", tokens.kaken)
            .replace("{apx}", tokens.apx)
        log("GX", "fixedApi='$fixedApi'")

        // ── 7. Fetch playable stream list ──────────────────────────────────
        val apiRes = try {
            app.post(
                fixedApi, headers = headers,
                data = mapOf(
                    "pd" to tokens.pd, "ps" to tokens.ps,
                    "qsx" to tokens.qsx, "kaken" to tokens.kaken,
                    "apx" to tokens.apx
                )
            ).text
        } catch (e: Exception) {
            log("GX", "❌ api POST failed: ${e.message}")
            logExit("GX", false, "api POST failed")
            return
        }
        log("GX", "api POST OK, len=${apiRes.length}")

        val apiPlain = dcx(apiRes.trim(), tokens.kaken)
            ?: dcx(apiRes.trim(), tokens.apx)
            ?: apiRes.trim()
        log("GX", "apiPlain preview: ${apiPlain.take(400)}")

        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""")
            .find(apiPlain)?.groupValues?.get(1) ?: gxBase
        log("GX", "baseURL='$baseURL'")

        val playbackHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to gxBase,
            "Origin" to gxBase
        )

        var emittedStreams = 0
        Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""")
            .findAll(apiPlain)
            .forEach { m ->
                val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: return@forEach
                val label = m.groupValues[2].ifBlank { "Auto" }
                val type = m.groupValues[3]
                val isM3u8 = streamUrl.contains(".m3u8") ||
                        type.contains("hls", true) ||
                        type.contains("m3u8", true)
                log("GX", "  ✓ stream label='$label' type='$type'")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} – $label",
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = gxBase
                        this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: 0
                        this.headers = playbackHeaders
                    }
                )
                emittedStreams++
            }

        var emittedSubs = 0
        Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""")
            .findAll(apiPlain)
            .forEach { m ->
                val subUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
                val lang = m.groupValues[2].ifBlank { "Sub" }
                subtitleCallback.invoke(newSubtitleFile(lang = lang, url = subUrl))
                emittedSubs++
            }

        logExit("GX", emittedStreams > 0, "streams=$emittedStreams subs=$emittedSubs")
    }

    /**
     * Scans arbitrary text for candidate API paths. Adds any absolute or
     * relative URLs matching the patterns to [out].
     */
    private fun scanForApiPaths(text: String, source: String, out: MutableSet<String>) {
        // Full absolute URLs
        val absRegex = Regex("""https?://[^\s"'<>\\]+?""")
        absRegex.findAll(text).forEach { m ->
            val u = m.value.trimEnd('.', ',', ')', '(', ';', ':')
            // Only keep URLs that point to galaxydonghua or contain /api/ or /wp-json/
            if (u.contains("galaxydonghua", true) ||
                u.contains("/api/", true) ||
                u.contains("/wp-json/", true)
            ) {
                // Extract only the path portion if same host
                if (u.contains("galaxydonghua.xyz")) {
                    val path = u.substringAfter("galaxydonghua.xyz")
                    if (path.isNotBlank() && (path.startsWith("/api/") || path.startsWith("/wp-json/"))) {
                        out.add(path.substringBefore("'").substringBefore("\""))
                    }
                } else {
                    out.add(u)
                }
            }
        }

        // Relative /api/ and /wp-json/ paths
        val relRegex = Regex("""["'](/(?:api|wp-json)/[A-Za-z0-9_\-/.?=&]+)["']""")
        relRegex.findAll(text).forEach { m ->
            out.add(m.groupValues[1])
        }
    }

    /**
     * A valid config response must contain `{` AND not be a "fail" message.
     */
    private fun isAcceptableConfig(body: String): Boolean {
        val t = body.trim()
        if (t.length < 15) return false
        if (!t.contains("{")) return false
        if (t.contains("404 Not Found", true)) return false
        if (t.contains("\"status\":\"fail\"", true)) return false
        if (t.contains("\"status\": \"fail\"", true)) return false
        if (t.contains("\"message\":\"Not Found\"", true)) return false
        if (t.contains("\"message\": \"Not Found\"", true)) return false
        if (t.startsWith("<") && !t.contains("{")) return false
        return true
    }

    // ... (all other helper methods unchanged: decodeGdTokens, stripConstants,
    //      splitTopLevelTerms, evalArithmetic, packrBase36, dcx, pbkdf2Sha256,
    //      aesDecrypt, fixStreamUrl, embedHost)
    // ... 
}

// ---------------------------------------------------------------------------
// 3. DailymotionExtractor — FIXED: nested subtitles.data[lang].url
// ---------------------------------------------------------------------------
class DailymotionExtractor : ExtractorApi() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("DM", url, referer)

        val videoId = extractVideoId(url)
        if (videoId == null) {
            log("DM", "❌ could not extract video ID from: $url")
            logExit("DM", false, "no video ID")
            return
        }
        log("DM", "videoId='$videoId'")

        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        log("DM", "fetching metadataUrl='$metadataUrl'")

        val html = try {
            val h = app.get(metadataUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://www.dailymotion.com/",
                "Accept" to "application/json"
            )).text
            log("DM", "metadata fetch OK, len=${h.length}")
            h
        } catch (e: Exception) {
            log("DM", "❌ metadata fetch failed: ${e.message}")
            logExit("DM", false, "fetch failed")
            return
        }

        val json = try {
            JSONObject(html)
        } catch (e: Exception) {
            log("DM", "❌ JSON parse failed: ${e.message}")
            logExit("DM", false, "JSON parse failed")
            return
        }

        var emittedStreams = 0
        val qualities = json.optJSONObject("qualities")
        if (qualities != null) {
            val qualityNames = qualities.names()
            if (qualityNames != null) {
                for (idx in 0 until qualityNames.length()) {
                    val key = qualityNames.optString(idx)
                    val qualityArray = qualities.optJSONArray(key) ?: continue

                    for (i in 0 until qualityArray.length()) {
                        val qualityObj = qualityArray.optJSONObject(i) ?: continue
                        val streamUrl = qualityObj.optString("url")
                        if (streamUrl.isBlank()) continue
                        val type = qualityObj.optString("type", "video/mp4")
                        val isM3u8 = streamUrl.contains(".m3u8") ||
                                type.contains("m3u8", true) ||
                                type.contains("mpegurl", true)

                        log("DM", "    ✓ quality key='$key' type='$type'")

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} – $key",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.dailymotion.com/"
                                this.quality = key.filter { it.isDigit() }.toIntOrNull()
                                    ?: Qualities.Unknown.value
                            }
                        )
                        emittedStreams++
                    }
                }
            }
        }

        var emittedSubs = 0
        val subtitles = json.optJSONObject("subtitles")
        log("DM", "subtitles present=${subtitles != null}")
        if (subtitles != null) {
            // Dailymotion nests real subtitles inside `data.{langCode}.url`.
            // Other keys like `enable` are boolean flags we should ignore.
            val dataNode = subtitles.optJSONObject("data")
            val subtitleSource = dataNode ?: subtitles

            val subNames = subtitleSource.names()
            if (subNames != null) {
                for (idx in 0 until subNames.length()) {
                    val langCode = subNames.optString(idx)
                    if (langCode == "enable" || langCode == "data") continue

                    val subVal = subtitleSource.opt(langCode)
                    val subUrl: String? = when (subVal) {
                        is String -> subVal.takeIf { it.startsWith("http", true) }
                        is JSONObject -> subVal.optString("url")
                            .takeIf { it.startsWith("http", true) }
                        else -> null
                    }

                    if (subUrl.isNullOrBlank()) {
                        log("DM", "  ⚠ skipping non-URL subtitle '$langCode'")
                        continue
                    }

                    log("DM", "  ✓ subtitle lang='$langCode' url=$subUrl")
                    subtitleCallback.invoke(newSubtitleFile(langCode, subUrl))
                    emittedSubs++
                }
            }
        }

        logExit("DM", emittedStreams > 0, "streams=$emittedStreams subs=$emittedSubs")
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/embed/video/([a-zA-Z0-9]+)"""),
            Regex("""/video/([a-zA-Z0-9]+)"""),
            Regex("""[?&]video=([a-zA-Z0-9]+)"""),
            Regex("""dai\.ly/([a-zA-Z0-9]+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) {
                val id = m.groupValues[1]
                if (id.isNotBlank()) return id
            }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// 4. GoogleDriveExtractor — unchanged
// ---------------------------------------------------------------------------
class GoogleDriveExtractor : ExtractorApi() {
    override var name = "Google Drive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    private val idPatterns = listOf(
        Regex("""/file/d/([a-zA-Z0-9_-]{10,})"""),
        Regex("""[?&]id=([a-zA-Z0-9_-]{10,})"""),
        Regex("""/d/([a-zA-Z0-9_-]{10,})""")
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("GDrive", url, referer)

        var fileId: String? = null
        for (pattern in idPatterns) {
            val m = pattern.find(url)
            if (m != null) {
                fileId = m.groupValues.getOrNull(1)
                break
            }
        }
        if (fileId == null) {
            log("GDrive", "❌ no file ID matched")
            logExit("GDrive", false, "no file ID")
            return
        }
        log("GDrive", "fileId='$fileId'")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://drive.google.com/"
        )

        val directUrl = resolveDirectUrl(fileId, headers)
        if (directUrl == null) {
            log("GDrive", "❌ resolveDirectUrl returned null")
            logExit("GDrive", false, "resolve failed")
            return
        }
        log("GDrive", "✓ directUrl='$directUrl'")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = directUrl,
                type = if (directUrl.contains(".m3u8", true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        logExit("GDrive", true, "callback invoked")
    }

    private suspend fun resolveDirectUrl(
        fileId: String,
        headers: Map<String, String>
    ): String? {
        val downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        log("GDrive.resolve", "probing $downloadUrl")

        val first = try {
            app.get(downloadUrl, headers = headers, allowRedirects = false)
        } catch (e: Exception) {
            log("GDrive.resolve", "❌ probe failed: ${e.message}")
            return null
        }

        log("GDrive.resolve", "HTTP status=${first.code}")

        val location = first.headers["Location"] ?: first.headers["location"]
        log("GDrive.resolve", "Location='$location'")

        if (!location.isNullOrBlank() &&
            (location.contains(".mp4", true) ||
                    location.contains(".mkv", true) ||
                    location.contains(".webm", true) ||
                    location.contains("videoplayback", true))
        ) {
            return location
        }

        val body = first.text
        log("GDrive.resolve", "body length=${body.length}")

        if (body.isBlank()) {
            if (!location.isNullOrBlank() && location.startsWith("http")) return location
            return null
        }

        val uuid = Regex("""name="uuid"\s+value="([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""uuid=([a-zA-Z0-9_-]+)""")
                .find(body)?.groupValues?.getOrNull(1)

        val confirm = Regex("""name="confirm"\s+value="([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: "t"

        if (!uuid.isNullOrBlank()) {
            val confirmUrl =
                "https://drive.usercontent.google.com/download" +
                        "?id=$fileId&export=download&confirm=$confirm&uuid=$uuid"
            log("GDrive.resolve", "Case B: confirmUrl='$confirmUrl'")

            val confirmed = try {
                app.get(confirmUrl, headers = headers, allowRedirects = false)
            } catch (e: Exception) { null }

            val confirmedLoc = confirmed?.headers?.get("Location")
                ?: confirmed?.headers?.get("location")
            if (!confirmedLoc.isNullOrBlank() && confirmedLoc.startsWith("http")) {
                return confirmedLoc
            }
            return confirmUrl
        }

        val legacyConfirm = Regex("""confirm=([0-9A-Za-z_-]+)""")
            .find(body)?.groupValues?.getOrNull(1)
        if (!legacyConfirm.isNullOrBlank()) {
            return "$downloadUrl&confirm=$legacyConfirm"
        }

        return downloadUrl
    }
}
