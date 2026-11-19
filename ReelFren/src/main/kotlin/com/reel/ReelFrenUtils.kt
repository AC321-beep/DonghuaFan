package com.reel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.WebSettings
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response
import android.webkit.CookieManager

object ReelFrenLog {
    private const val TAG = "ReelFren/CF"
    fun d(scope: String, msg: String) {
        Log.e(TAG, "[$scope][${Thread.currentThread().name}] $msg")
    }
    fun t(scope: String, e: Throwable) {
        Log.e(TAG, "[$scope][${Thread.currentThread().name}] ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

data class DetailInfo(
    val id: String, val provider: String, val title: String,
    val cover: String, val intro: String, val episodes: Int,
    val videos: List<VideoEntry>
)
data class HomeItem(
    val id: String, val provider: String, val title: String,
    val cover: String, val intro: String, val episodes: Int
)
data class PlaybackInfo(
    val title: String, val episodeNumber: Int, val totalEpisodes: Int,
    val locked: Boolean, val qualities: List<QualityEntry>,
    val subtitles: List<SubtitleEntry>, val server: String
)
data class QualityEntry(val label: String, val url: String, val format: String)
data class SubtitleEntry(val label: String, val lang: String, val url: String)
data class VideoEntry(val episode: Int, val duration: Int, val vid: String, val available: Boolean)

object ReelFrenConfig {
    const val REEL_SITE = "https://reelfren.net"
    const val DEFAULT_API_BASE = "https://reelfren.net"

    val DEFAULT_ENABLED_SLUGS = setOf(
        "dramabox", "reelshort", "wetv", "happyshort", "shorten",
        "dramawave", "shortmax", "netshort", "pinedrama", "vigloo"
    )

    val PROVIDERS_MAP: LinkedHashMap<String, String> = linkedMapOf(
        "Anamana"         to "anamana",
        "AniBoy"          to "aniboy",
        "Melolo"          to "melolo",
        "Sereal+"         to "sereal",
        "PineDrama"       to "pinedrama",
        "Shorten"         to "shorten",
        "HappyShort"      to "happyshort",
        "Vigloo"          to "vigloo",
        "Vigloo 2"        to "vigloo2",
        "RapidTV"         to "rapidtv",
        "RapidTV 2"       to "rapidtv2",
        "RaptDrama"       to "raptdrama",
        "CubeTV"          to "cubetv",
        "JoyReels"        to "joyreels",
        "AnyReel"         to "anyreel",
        "Shortical"       to "shortical",
        "StoReel"         to "storeel",
        "LoveShots"       to "loveshots",
        "MomeShort"       to "momeshort",
        "MoreShort"       to "moreshort",
        "ToonShort"       to "toonshort",
        "SixthShort"      to "sixthshort",
        "SoReel"          to "soreel",
        "StoryGo"         to "storygo",
        "MiniShort"       to "minishort",
        "WinkNovel"       to "winknovel",
        "My Muse"         to "mymuse",
        "SarosTV"         to "sarostv",
        "ReelAI"          to "reelai",
        "Reelife"         to "reelife",
        "ReelShort"       to "reelshort",
        "ReelShort 2"     to "reelshort2",
        "DramaBox"        to "dramabox",
        "DramaWave"       to "dramawave",
        "DramaNova"       to "dramanova",
        "KalosTV"         to "kalostv",
        "VibeShort"       to "vibeshort",
        "FreeReels"       to "freereels",
        "WeTV"            to "wetv",
        "StoryReel"       to "storyreel",
        "MovieBox Shorts" to "movieboxshorts",
        "BonusTV"         to "bonustv",
        "MoboReels"       to "moboreels",
        "NetShort"        to "netshort",
        "MyDrama"         to "mydrama",
        "FlareFlow"       to "flareflow",
        "PlayLet"         to "playlet",
        "ShortMax"        to "shortmax",
        "FlexTV"          to "flextv",
        "FlexTV 2"        to "flextv2",
        "CandyJar"        to "candyjar",
        "BlinkDrama"      to "blinkdrama",
        "iQIYI"           to "iqiyi",
        "MovieBox"        to "moviebox",
        "FilmBox"         to "filmbox"
    )
}

object ReelFrenParse {
    private val SUB_ARRAY_KEYS = arrayOf("subtitles", "subtitleList", "subs", "tracks", "captions", "captionList")
    private val SUB_DIRECT_KEYS = arrayOf("subtitleUrl", "subUrl", "subtitle", "vtt", "srt", "captionUrl", "webvtt")
    private val SUB_URL_KEYS = arrayOf("url", "src", "file", "subUrl", "subtitleUrl", "vtt", "srt", "link", "path", "webvtt")
    private val SUB_LANG_KEYS = arrayOf("lang", "srclang", "srcLang", "language", "code", "locale", "langCode", "iso", "languageCode", "language_code", "lang_code")
    private val SUB_LABEL_KEYS = arrayOf("label", "name", "title", "display", "langName", "language")

    fun homeItems(body: String): List<HomeItem> {
        if (body.isBlank()) {
            ReelFrenLog.d("Parse.homeItems", "Empty response body (len=0)")
            return emptyList()
        }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: run {
            ReelFrenLog.d("Parse.homeItems", "Invalid JSON (len=${body.length})")
            return emptyList()
        }
        val status = root.optInt("status", 200)
        if (status >= 400 || root.has("error")) {
            ReelFrenLog.d("Parse.homeItems", "Upstream API error (status=$status, title='${root.optString("title")}', body='${body.take(200)}')")
            return emptyList()
        }
        val dataObj = root.optJSONObject("data")
        val arr = root.optJSONArray("data")
            ?: dataObj?.optJSONArray("items")
            ?: dataObj?.optJSONArray("list")
            ?: dataObj?.optJSONArray("results")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("list")
            ?: run {
                ReelFrenLog.d("Parse.homeItems", "No items array (len=${body.length}): ${body.take(180)}")
                return emptyList()
            }

        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = clean(o, "id")
            val title = clean(o, "title")
            if (id.isEmpty() || title.isEmpty()) null
            else HomeItem(id = id, provider = clean(o, "provider"), title = title, cover = clean(o, "cover"), intro = clean(o, "intro"), episodes = o.optInt("episodes"))
        }
    }

    fun detail(body: String): DetailInfo? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("detail") ?: root.optJSONObject("data") ?: root
        val id = clean(o, "id")
        val title = clean(o, "title")
        if (id.isEmpty() || title.isEmpty()) return null
        val videos = mutableListOf<VideoEntry>()
        o.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                videos += VideoEntry(episode = v.optInt("episode", i + 1), duration = v.optInt("duration"), vid = clean(v, "vid"), available = v.optBoolean("available", true))
            }
        }
        return DetailInfo(id = id, provider = clean(o, "provider"), title = title, cover = clean(o, "cover"), intro = clean(o, "intro"), episodes = o.optInt("episodes", videos.size), videos = videos.sortedBy { it.episode })
    }

    fun playback(body: String): PlaybackInfo? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("data") ?: root.optJSONObject("playback") ?: root

        val qualities = mutableListOf<QualityEntry>()
        val subs = mutableListOf<SubtitleEntry>()

        val qArr = o.optJSONArray("qualityList") ?: o.optJSONArray("qualities") ?: o.optJSONArray("sources")
        qArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                val url = firstNonEmpty(q, "url", "src", "file", "videoUrl")
                if (url.isNotEmpty()) {
                    qualities += QualityEntry(label = firstNonEmpty(q, "label", "quality", "name").ifEmpty { "Auto" }, url = url, format = clean(q, "format"))
                }
                collectSubtitlesFromNode(q, subs)
            }
        }

        val direct = firstNonEmpty(o, "videoUrl", "url", "streamUrl", "playUrl", "m3u8")
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            qualities.add(0, QualityEntry("Auto", direct, ReelFrenQuality.inferFormat(direct)))
        }
        if (qualities.isEmpty()) return null

        collectSubtitlesFromNode(o, subs)
        if (o !== root) collectSubtitlesFromNode(root, subs)

        return PlaybackInfo(
            title = clean(o, "title"),
            episodeNumber = o.optInt("episodeNumber", 1),
            totalEpisodes = o.optInt("totalEpisodes", 1),
            locked = o.optBoolean("locked"),
            qualities = qualities,
            subtitles = subs.distinctBy { "${it.lang.lowercase(Locale.ROOT)}|${it.url}" },
            server = clean(o, "sourceServer").ifEmpty { "1" }
        )
    }

    private fun collectSubtitlesFromNode(node: JSONObject, out: MutableList<SubtitleEntry>) {
        for (key in SUB_ARRAY_KEYS) {
            node.optJSONArray(key)?.let { arr -> parseSubtitleArray(arr, out) }
            node.optJSONObject(key)?.let { obj -> parseSubtitleObjectOrMap(obj, out) }
        }
        node.optJSONObject("subtitle")?.let { obj -> parseSubtitleObjectOrMap(obj, out) }
        val fallbackLang = firstNonEmpty(node, "lang", "language", "srclang").ifEmpty { "en" }
        for (key in SUB_DIRECT_KEYS) {
            if (node.optJSONObject(key) != null || node.optJSONArray(key) != null) continue
            val directUrl = clean(node, key)
            if (looksLikeSubtitleUrl(directUrl)) out += SubtitleEntry(label = clean(node, "subtitleLabel").ifEmpty { fallbackLang }, lang = fallbackLang, url = directUrl)
        }
    }

    private fun parseSubtitleArray(arr: JSONArray, out: MutableList<SubtitleEntry>) {
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i)
            if (s != null) {
                parseSingleSubtitleObject(s)?.let { out += it }
            } else {
                val rawUrl = arr.optString(i, "").trim()
                if (looksLikeSubtitleUrl(rawUrl)) {
                    val guessedLang = guessLangFromUrl(rawUrl)
                    val label = if (guessedLang == "und") "Subtitle ${i + 1}" else guessedLang.uppercase()
                    out += SubtitleEntry(label = label, lang = guessedLang, url = rawUrl)
                }
            }
        }
    }

    private fun parseSubtitleObjectOrMap(obj: JSONObject, out: MutableList<SubtitleEntry>) {
        parseSingleSubtitleObject(obj)?.let { out += it; return }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val nestedObj = obj.optJSONObject(k)
            if (nestedObj != null) {
                val entry = parseSingleSubtitleObject(nestedObj, defaultLang = k)
                if (entry != null) out += entry
            } else {
                val rawUrl = clean(obj, k)
                if (looksLikeSubtitleUrl(rawUrl)) out += SubtitleEntry(label = k, lang = k, url = rawUrl)
            }
        }
    }

    private fun parseSingleSubtitleObject(s: JSONObject, defaultLang: String = ""): SubtitleEntry? {
        val kind = firstNonEmpty(s, "kind", "type").lowercase(Locale.ROOT)
        if (kind == "thumbnails" || kind == "chapters" || kind == "metadata") return null
        val u = firstNonEmpty(s, *SUB_URL_KEYS)
        if (!looksLikeSubtitleUrl(u)) return null
        val lang = firstNonEmpty(s, *SUB_LANG_KEYS).ifEmpty { defaultLang }
        return SubtitleEntry(label = firstNonEmpty(s, *SUB_LABEL_KEYS).ifEmpty { lang }, lang = lang, url = u)
    }

    private fun looksLikeSubtitleUrl(u: String): Boolean {
        val trimmed = u.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == "null" || lower == "none" || lower == "false" || lower == "0" || lower == "#" || lower == "hardcoded") return false
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//") || lower.startsWith("/") || lower.startsWith("data:")
    }

    private fun guessLangFromUrl(url: String): String {
        val filename = url.substringAfterLast('/').substringBefore('?')
        val match = Regex("(?:^|[._-])(en|es|pt|id|ar|fr|de|it|ru|zh|ja|ko|hi|vi|th|ms|tr)(?:[._-]|$)", RegexOption.IGNORE_CASE).find(filename)
        if (match != null) {
            val code = match.groupValues[1].lowercase()
            return when (code) { "jp" -> "ja"; "kr" -> "ko"; "in" -> "id"; else -> code }
        }
        val lowerUrl = url.lowercase()
        val langs = listOf("english" to "en", "spanish" to "es", "indonesian" to "id", "portuguese" to "pt", "arabic" to "ar", "french" to "fr", "german" to "de", "japanese" to "ja", "korean" to "ko", "chinese" to "zh")
        for ((name, code) in langs) {
            if (lowerUrl.contains(name)) return code
        }
        return "und"
    }

    private fun firstNonEmpty(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = clean(o, k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun clean(o: JSONObject, key: String, fallback: String = ""): String {
        val v = o.optString(key, fallback).trim()
        return if (v.equals("null", ignoreCase = true) || v.equals("undefined", ignoreCase = true)) fallback else v
    }
}

object ReelFrenStore {
    @Volatile var appContext: Context? = null
        private set
    @Volatile var defaultSystemUa: String = ReelFrenClient.FALLBACK_UA
        private set

    @Volatile private var storePrefs: SharedPreferences? = null
    @Volatile private var providerPrefs: SharedPreferences? = null

    private val wvVersionRegex = Regex("""Version/\d+\.\d+\s*""")

    fun normalizeUa(rawUa: String): String =
        rawUa.replace("; wv", "").replace(wvVersionRegex, "").trim()

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        storePrefs = app.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
        providerPrefs = app.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)

        runCatching {
            normalizeUa(WebSettings.getDefaultUserAgent(app))
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ua ->
            defaultSystemUa = ua
        }
    }

    fun isProviderEnabled(slug: String): Boolean {
        val default = ReelFrenConfig.DEFAULT_ENABLED_SLUGS.contains(slug)
        return providerPrefs?.getBoolean("provider_$slug", default) ?: default
    }

    fun setProviderEnabled(editor: SharedPreferences.Editor, slug: String, enabled: Boolean) {
        editor.putBoolean("provider_$slug", enabled)
    }

    fun providerPrefsEditor(): SharedPreferences.Editor? = providerPrefs?.edit()

    fun enabledProviders(): Map<String, String> =
        ReelFrenConfig.PROVIDERS_MAP.filter { (_, slug) -> isProviderEnabled(slug) }

    fun apiBase(): String {
        val raw = storePrefs?.getString("api_base", ReelFrenConfig.DEFAULT_API_BASE).orEmpty()
        val clean = raw.trim().trimEnd('/')
        return if (clean.startsWith("https://") || clean.startsWith("http://")) clean else ReelFrenConfig.DEFAULT_API_BASE
    }

    fun shouldSkipPrewarm(): Boolean {
        val lastFail = storePrefs?.getLong("prewarm_last_fail", 0L) ?: 0L
        val failCount = storePrefs?.getInt("prewarm_fail_count", 0) ?: 0
        if (lastFail == 0L || failCount <= 0) return false
        val shift = (failCount - 1).coerceIn(0, 6)
        val cooldown = 60_000L * (1L shl shift)
        return (System.currentTimeMillis() - lastFail) < cooldown
    }

    fun recordPrewarmResult(success: Boolean) {
        val editor = storePrefs?.edit() ?: return
        if (success) {
            editor.putLong("prewarm_last_fail", 0L)
            editor.putInt("prewarm_fail_count", 0)
        } else {
            val count = (storePrefs?.getInt("prewarm_fail_count", 0) ?: 0) + 1
            editor.putLong("prewarm_last_fail", System.currentTimeMillis())
            editor.putInt("prewarm_fail_count", count)
        }
        editor.apply()
    }
}

object ReelFrenQuality {
    private val heavy = Regex("(?i)\\b(h\\.?26[56]|hevc|x\\.?26[56]|av1)\\b")
    private val resRegex = Regex("(?i)(\\d{3,4})\\s*p\\b")

    fun label(raw: String) = raw.trim().ifEmpty { "Auto" }

    fun inferFormat(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> "hls"
        url.contains(".mpd", ignoreCase = true)  -> "dash"
        else -> "video"
    }

    fun linkType(url: String, format: String = ""): ExtractorLinkType = when {
        format.equals("hls", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        format.equals("dash", ignoreCase = true) || url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    fun of(label: String): Int {
        val lower = label.lowercase(Locale.ROOT)
        val base = resRegex.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("720")  -> 720
            lower.contains("480")  -> 480
            lower.contains("360")  -> 360
            else -> 400
        }
        return if (heavy.containsMatchIn(label)) demote(base) else base
    }

    private fun demote(q: Int) = when {
        q >= 2160 -> 1080
        q >= 1080 -> 720
        q >= 720  -> 480
        else      -> 360
    }
}

object ReelFrenUrl {
    private val pathRegex = Regex("/(?:drama|watch)/([^/?#|]+)/([^/?#|]+)")
    private val epRegex = Regex("[?&]ep=(\\d+)")

    fun page(slug: String, id: String) = "${ReelFrenConfig.REEL_SITE}/drama/$slug/$id"
    fun episode(slug: String, id: String, ep: Int) = "${ReelFrenConfig.REEL_SITE}/watch/$slug/$id?ep=$ep"

    fun parse(raw: String?): Triple<String, String, Int> {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return Triple("", "", 1)

        pathRegex.find(input)?.let { m ->
            val slug = m.groupValues[1].trim().lowercase(Locale.ROOT)
            val id   = m.groupValues[2].trim()
            val ep   = epRegex.find(input)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            if (slug.isNotEmpty() && id.isNotEmpty()) return Triple(slug, id, ep)
        }

        val body = input.substringAfter("://", input)
            .substringAfter("/", "")
            .substringBefore("?")
            .substringBefore("#")
        val parts = body.split("|")
        val slug = parts.getOrNull(0)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id   = parts.getOrNull(1)?.trim().orEmpty()
        val ep   = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
        return if (slug.isNotEmpty() && id.isNotEmpty()) Triple(slug, id, ep) else Triple("", "", 1)
    }
}

// ── Interceptor ─────────────────────────────────────────────────────────────
class ReelFrenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val builder = original.newBuilder()

        val ua = ReelFrenCfState.userAgentFor(host) ?: ReelFrenClient.FALLBACK_UA
        builder.header("User-Agent", ua)
        
        builder.removeHeader("X-Requested-With")

        val existing = original.header("Cookie")
        val managed = CookieManager.getInstance().getCookie(original.url.toString())
        val merged = listOfNotNull(existing, managed).filter { it.isNotEmpty() }.joinToString("; ")
        if (merged.isNotEmpty()) {
            builder.header("Cookie", merged)
        }

        // Must inject Accept: application/json for API requests so Next.js returns JSON instead of HTML
        if (original.url.encodedPath.startsWith("/api/")) {
            builder.header("Accept", "application/json, text/plain, */*")
        } else {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}

// ── HTTP Client with Interceptor & Spoofed TLS ─────────────────────────────────────────────
object ReelFrenClient {

    const val FALLBACK_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val siteHost by lazy { ReelFrenCloudflare.hostOf(ReelFrenConfig.REEL_SITE) }

    private val chromeSpec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
        .tlsVersions(okhttp3.TlsVersion.TLS_1_2, okhttp3.TlsVersion.TLS_1_3)
        .cipherSuites(
            okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
            okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
            okhttp3.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
        )
        .build()

    // Spoofed OkHttp Client paired with our Interceptor
    private val spoofedClient = app.baseClient.newBuilder()
        .addInterceptor(ReelFrenInterceptor())
        .connectionSpecs(listOf(chromeSpec, okhttp3.ConnectionSpec.CLEARTEXT))
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    private val spoofedApp = com.lagradost.cloudstream3.Requests(spoofedClient)

    private fun apiBase() = ReelFrenStore.apiBase()
    private fun query(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun isSiteDomain(host: String): Boolean =
        host.isNotEmpty() && (host == siteHost || host.endsWith(".$siteHost"))

    fun homeUrl(slug: String, category: String = "", page: Int = 1): String {
        val p = mutableListOf<String>()
        val isGlobal = slug == "all_dramas"
        
        if (!isGlobal && slug.isNotEmpty()) p += "provider=${query(slug)}"
        p += "lang=en"
        if (category.isNotEmpty()) p += "category=${query(category)}"
        val off = page - 1
        if (off > 0) p += "offset=$off"
        
        return "${apiBase()}/api/home?${p.joinToString("&")}"
    }

    fun detailUrl(slug: String, id: String) =
        "${apiBase()}/api/detail?provider=${query(slug)}&id=${query(id)}&lang=en"

    fun videoUrl(slug: String, id: String, ep: Int) =
        "${apiBase()}/api/video?provider=${query(slug)}&id=${query(id)}&ep=$ep"

    fun searchUrl(slug: String, term: String) =
        "${apiBase()}/api/search?q=${query(term)}&provider=${query(slug)}&lang=en"

    fun headers(url: String): Map<String, String> {
        val host = ReelFrenCloudflare.hostOf(url)
        val map = linkedMapOf<String, String>()
        if (isSiteDomain(host)) {
            map["Origin"] = ReelFrenConfig.REEL_SITE
            map["Referer"] = "${ReelFrenConfig.REEL_SITE}/"
        }
        return map
    }

    fun posterHeaders(posterUrl: String?): Map<String, String>? {
        if (posterUrl.isNullOrEmpty()) return null
        return if (isSiteDomain(ReelFrenCloudflare.hostOf(posterUrl))) headers(posterUrl) else null
    }

    suspend fun get(url: String): String? {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val isReelFrenApi = isSiteDomain(targetHost)
        
        // 1. Initial attempt using Spoofed Client (Interceptor injects WebView cookies & UA automatically)
        ReelFrenLog.d("Client.get", "Attempting request via Spoofed OkHttp: $url")
        var responseText = try {
            if (isReelFrenApi) {
                spoofedApp.get(url, headers = headers(url), timeout = 25L, cacheTime = 0).text
            } else {
                app.get(url, headers = headers(url), timeout = 25L).text
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Client.get/fetch1", e)
            ""
        }

        val isChallenged = responseText.isEmpty() || ReelFrenCloudflare.isChallengeResponse(200, null, responseText)
        
        if (!isChallenged) {
             return responseText
        }

        ReelFrenLog.d("Client.get", "Challenge detected. Delegating to CFSolver on root site.")
        
        // CF is blocking us. Solve the challenge strictly on the root HTML site.
        val solver = CFSolver(ReelFrenConfig.REEL_SITE)
        val solved = solver.solve()
        
        if (!solved) {
            ReelFrenLog.d("Client.get", "CFSolver failed (dismissed or timed out). Returning null.")
            return null
        }

        // 2. Retry the API fetch using Spoofed OkHttp now that CookieManager holds the fresh clearance
        responseText = try {
            if (isReelFrenApi) {
                spoofedApp.get(url, headers = headers(url), timeout = 25L, cacheTime = 0).text
            } else {
                app.get(url, headers = headers(url), timeout = 25L).text
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Client.get/fetch2", e)
            ""
        }
        
        val isStillChallenged = responseText.isEmpty() || ReelFrenCloudflare.isChallengeResponse(200, null, responseText)
        
        if (isStillChallenged) {
            ReelFrenLog.d("Client.get", "Spoofed OkHttp fetch failed even after solve. Giving up.")
            return null
        }

        return responseText
    }

    suspend fun home(slug: String, category: String = "", page: Int = 1): List<HomeItem> =
        get(homeUrl(slug, category, page))?.let(ReelFrenParse::homeItems) ?: emptyList()

    suspend fun detail(slug: String, id: String): DetailInfo? =
        get(detailUrl(slug, id))?.let(ReelFrenParse::detail)

    suspend fun playback(slug: String, id: String, ep: Int): PlaybackInfo? =
        get(videoUrl(slug, id, ep))?.let(ReelFrenParse::playback)

    suspend fun search(slug: String, term: String): List<HomeItem> =
        get(searchUrl(slug, term))?.let(ReelFrenParse::homeItems) ?: emptyList()

    fun absUrl(value: String): String {
        val clean = value.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("https://", true) || clean.startsWith("http://", true)) return clean
        if (clean.startsWith("//")) return "https:$clean"
        val base = apiBase()
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }
}
