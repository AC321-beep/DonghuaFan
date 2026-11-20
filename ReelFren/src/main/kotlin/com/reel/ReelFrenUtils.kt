package com.reel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ── Unified Logger ───────────────────────────────────────────────────────────
object ReelFrenLog {
    private const val TAG = "ReelFren/CF"
    fun d(scope: String, msg: String) {
        Log.e(TAG, "[$scope][${Thread.currentThread().name}] $msg")
    }
    fun t(scope: String, e: Throwable) {
        Log.e(TAG, "[$scope][${Thread.currentThread().name}] ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

// ── Models ───────────────────────────────────────────────────────────────────
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

// ── Config & Provider Registry ───────────────────────────────────────────────
object ReelFrenConfig {
    const val REEL_SITE = "https://reelfren.net"
    const val DEFAULT_API_BASE = "https://api.reelfren.net"

    val DEFAULT_ENABLED_SLUGS = setOf(
        "dramabox", "reelshort", "wetv", "happyshort", "shorten",
        "dramawave", "shortmax", "netshort", "pinedrama", "vigloo"
    )

    val PROVIDERS_MAP: LinkedHashMap<String, String> = linkedMapOf(
        "Anamana"         to "anamana", "AniBoy"          to "aniboy",
        "Melolo"          to "melolo", "Sereal+"         to "sereal",
        "PineDrama"       to "pinedrama", "Shorten"         to "shorten",
        "HappyShort"      to "happyshort", "Vigloo"          to "vigloo",
        "Vigloo 2"        to "vigloo2", "RapidTV"         to "rapidtv",
        "RapidTV 2"       to "rapidtv2", "RaptDrama"       to "raptdrama",
        "CubeTV"          to "cubetv", "JoyReels"        to "joyreels",
        "AnyReel"         to "anyreel", "Shortical"       to "shortical",
        "StoReel"         to "storeel", "LoveShots"       to "loveshots",
        "MomeShort"       to "momeshort", "MoreShort"       to "moreshort",
        "ToonShort"       to "toonshort", "SixthShort"      to "sixthshort",
        "SoReel"          to "soreel", "StoryGo"         to "storygo",
        "MiniShort"       to "minishort", "WinkNovel"       to "winknovel",
        "My Muse"         to "mymuse", "SarosTV"         to "sarostv",
        "ReelAI"          to "reelai", "Reelife"         to "reelife",
        "ReelShort"       to "reelshort", "ReelShort 2"     to "reelshort2",
        "DramaBox"        to "dramabox", "DramaWave"       to "dramawave",
        "DramaNova"       to "dramanova", "KalosTV"         to "kalostv",
        "VibeShort"       to "vibeshort", "FreeReels"       to "freereels",
        "WeTV"            to "wetv", "StoryReel"       to "storyreel",
        "MovieBox Shorts" to "movieboxshorts", "BonusTV"         to "bonustv",
        "MoboReels"       to "moboreels", "NetShort"        to "netshort",
        "MyDrama"         to "mydrama", "FlareFlow"       to "flareflow",
        "PlayLet"         to "playlet", "ShortMax"        to "shortmax",
        "FlexTV"          to "flextv", "FlexTV 2"        to "flextv2",
        "CandyJar"        to "candyjar", "BlinkDrama"      to "blinkdrama",
        "iQIYI"           to "iqiyi", "MovieBox"        to "moviebox",
        "FilmBox"         to "filmbox"
    )
}

// ── JSON Parser ──────────────────────────────────────────────────────────────
object ReelFrenParse {
    private val SUB_DIRECT_KEYS = arrayOf("subtitleUrl", "subUrl", "subtitle", "vtt", "srt", "captionUrl", "webvtt")
    private val SUB_URL_KEYS = arrayOf("url", "src", "file", "subUrl", "subtitleUrl", "vtt", "srt", "link", "path", "webvtt")
    private val SUB_LANG_KEYS = arrayOf("lang", "srclang", "srcLang", "language", "code", "locale", "langCode", "iso", "languageCode", "name")
    private val SUB_LABEL_KEYS = arrayOf("label", "name", "title", "display", "langName", "language")

    fun homeItems(body: String): List<HomeItem> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val dataObj = root.optJSONObject("data")
        val arr = root.optJSONArray("data") ?: dataObj?.optJSONArray("items") ?: dataObj?.optJSONArray("list") ?: dataObj?.optJSONArray("results") ?: root.optJSONArray("items") ?: root.optJSONArray("list") ?: return emptyList()

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
                // Scan for subtitles inside the quality objects
                collectSubtitlesFromNode(q, subs)
            }
        }

        val direct = firstNonEmpty(o, "videoUrl", "url", "streamUrl", "playUrl", "m3u8")
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            qualities.add(0, QualityEntry("Auto", direct, ReelFrenQuality.inferFormat(direct)))
        }
        if (qualities.isEmpty()) return null

        // Scan for subtitles in the main JSON structures
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

    // FIX 2: Aggressive Dynamic Subtitle Scanning
    private fun collectSubtitlesFromNode(node: JSONObject, out: MutableList<SubtitleEntry>) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lowerKey = key.lowercase(Locale.ROOT)
            
            // Dynamically catch ANY key that sounds like it holds subtitles (e.g. "subtitle_list", "captionsData")
            if (lowerKey.contains("sub") || lowerKey.contains("track") || lowerKey.contains("caption")) {
                node.optJSONArray(key)?.let { arr -> parseSubtitleArray(arr, out) }
                node.optJSONObject(key)?.let { obj -> parseSubtitleObjectOrMap(obj, out) }
            }
        }
        
        // Also check if the URL string is directly attached to the node
        for (key in SUB_DIRECT_KEYS) {
            if (node.optJSONObject(key) != null || node.optJSONArray(key) != null) continue
            val directUrl = clean(node, key)
            if (looksLikeSubtitleUrl(directUrl)) {
                val fallbackLang = firstNonEmpty(node, "lang", "language", "srclang").ifEmpty { "English" }
                out += SubtitleEntry(
                    label = clean(node, "subtitleLabel").ifEmpty { fallbackLang }, 
                    lang = fallbackLang, 
                    url = directUrl
                )
            }
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
                    out += SubtitleEntry(
                        label = if (guessedLang == "und") "Subtitle ${i + 1}" else guessedLang.uppercase(), 
                        lang = guessedLang, 
                        url = rawUrl
                    )
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
        val lang = firstNonEmpty(s, *SUB_LANG_KEYS).ifEmpty { defaultLang }.ifEmpty { "English" }
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
    
    private val memoryUa = ConcurrentHashMap<String, String>()
    private val memoryCookies = ConcurrentHashMap<String, String>()
    private val wvVersionRegex = Regex("""Version/\d+\.\d+\s*""")
    private val rootDomain by lazy { ReelFrenCloudflare.hostOf(ReelFrenConfig.REEL_SITE) }

    fun normalizeUa(rawUa: String): String = rawUa.replace("; wv", "").replace(wvVersionRegex, "").trim()

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        storePrefs = appContext?.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
        providerPrefs = appContext?.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)
        runCatching {
            normalizeUa(WebSettings.getDefaultUserAgent(ctx))
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

    fun cookie(host: String): String {
        if (host.isBlank()) return ""
        val norm = host.lowercase(Locale.ROOT)
        memoryCookies[norm]?.let { return it }
        val saved = storePrefs?.getString("cookie_$norm", "").orEmpty()
        if (saved.isNotEmpty()) {
            memoryCookies[norm] = saved
            return saved
        }
        if (norm != rootDomain && norm.endsWith(".$rootDomain")) {
            return cookie(rootDomain)
        }
        return ""
    }

    fun saveCookie(host: String, cookie: String) {
        if (host.isBlank()) return
        val norm = host.lowercase(Locale.ROOT)
        if (cookie.isBlank()) {
            memoryCookies.remove(norm)
        } else {
            memoryCookies[norm] = cookie
        }
        storePrefs?.edit()?.putString("cookie_$norm", cookie)?.apply()
    }

    fun userAgent(host: String): String {
        if (host.isBlank()) return defaultSystemUa
        val norm = host.lowercase(Locale.ROOT)
        memoryUa[norm]?.let { return it }
        val saved = storePrefs?.getString("ua_$norm", null)?.takeIf { it.isNotBlank() }
        if (saved != null) {
            val cleaned = normalizeUa(saved)
            memoryUa[norm] = cleaned
            return cleaned
        }
        if (norm != rootDomain && norm.endsWith(".$rootDomain")) {
            return userAgent(rootDomain)
        }
        return defaultSystemUa
    }

    fun setUserAgent(host: String, ua: String) {
        if (host.isBlank()) return
        val norm = host.lowercase(Locale.ROOT)
        val cleaned = normalizeUa(ua)
        memoryUa[norm] = cleaned
        storePrefs?.edit()?.putString("ua_$norm", cleaned)?.apply()
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

class WebViewCookieJar : CookieJar {
    private val cookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        for (cookie in cookies) {
            cookieManager.setCookie(urlString, cookie.toString())
        }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieStr = cookieManager.getCookie(url.toString())
        if (cookieStr.isNullOrEmpty()) return emptyList()

        return cookieStr.split(";").mapNotNull {
            Cookie.parse(url, it.trim())
        }
    }
}

object ReelFrenClient {

    const val FALLBACK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val apiHost by lazy { ReelFrenCloudflare.hostOf(ReelFrenConfig.DEFAULT_API_BASE) }
    private val apiMutex = Mutex()

    private val chromeSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        .cipherSuites(
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
        )
        .build()

    private val spoofedClient = app.baseClient.newBuilder()
        .cookieJar(WebViewCookieJar())
        .connectionSpecs(listOf(chromeSpec, ConnectionSpec.CLEARTEXT))
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val spoofedApp = com.lagradost.nicehttp.Requests(spoofedClient)

    private fun apiBase() = ReelFrenStore.apiBase()
    private fun query(v: String) = URLEncoder.encode(v, "UTF-8")
    
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

    fun detailUrl(slug: String, id: String) = "${apiBase()}/api/detail?provider=${query(slug)}&id=${query(id)}&lang=en"
    fun videoUrl(slug: String, id: String, ep: Int) = "${apiBase()}/api/video?provider=${query(slug)}&id=${query(id)}&ep=$ep"
    fun searchUrl(slug: String, term: String) = "${apiBase()}/api/search?q=${query(term)}&provider=${query(slug)}&lang=en"

    fun headers(url: String, isApiCall: Boolean = false): Map<String, String> {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val map = linkedMapOf<String, String>()
        
        map["User-Agent"] = ReelFrenStore.userAgent(targetHost)
        map["Accept-Language"] = "en-US,en;q=0.9"
        
        if (isApiCall) {
            map["Accept"] = "application/json, text/plain, */*"
        } else {
            map["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        }

        if (targetHost == apiHost || targetHost.contains("reelfren.net")) {
            map["Origin"] = ReelFrenConfig.REEL_SITE
            map["Referer"] = "${ReelFrenConfig.REEL_SITE}/"
            
            val cookie = ReelFrenStore.cookie(targetHost)
            if (cookie.isNotBlank()) map["Cookie"] = cookie
        }
        return map
    }

    fun posterHeaders(posterUrl: String?): Map<String, String>? {
        if (posterUrl.isNullOrEmpty()) return null
        val host = ReelFrenCloudflare.hostOf(posterUrl)
        if (!host.contains("reelfren.net")) return null
        return headers(posterUrl, isApiCall = false)
    }

    suspend fun get(url: String): String? {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val isReelFrenApi = targetHost == apiHost
        
        ReelFrenLog.d("Client.get", "[GET INIT] url=$url isApi=$isReelFrenApi")

        if (!isReelFrenApi) {
            return try {
                app.get(url, headers = headers(url, false), timeout = 25L).text
            } catch (e: Exception) { null }
        }

        ReelFrenLog.d("Client.get", "Attempting fast-path API fetch for: $url")
        var jsonText = try {
            spoofedApp.get(url, headers = headers(url, true), timeout = 25L).text
        } catch (e: Exception) { 
            ReelFrenLog.d("Client.get", "OkHttp EXCEPTION on fast-path: ${e.message}")
            "" 
        }

        if (jsonText.isNotEmpty() && !ReelFrenCloudflare.isChallengeResponse(jsonText)) {
            ReelFrenLog.d("Client.get", "✅ Fast-path succeeded! Length: ${jsonText.length}")
            return jsonText
        }

        ReelFrenLog.d("Client.get", "⚠️ Fast-path failed or challenged. Acquiring CF Mutex...")
        
        apiMutex.withLock {
            ReelFrenLog.d("Client.get", "🔒 Mutex acquired for $url. Double checking cache...")
            
            jsonText = try {
                spoofedApp.get(url, headers = headers(url, true), timeout = 25L).text
            } catch (e: Exception) { "" }
            
            if (jsonText.isNotEmpty() && !ReelFrenCloudflare.isChallengeResponse(jsonText)) {
                ReelFrenLog.d("Client.get", "🔓 Pre-cleared by previous provider! Proceeding.")
                return jsonText
            }

            ReelFrenLog.d("Client.get", "🛠️ Delegating to native CFSolver on ROOT site (reelfren.net)")
            val solved = ReelFrenCloudflare.solve(ReelFrenConfig.REEL_SITE)
            
            if (!solved) {
                ReelFrenLog.d("Client.get", "❌ CFSolver failed. Aborting fetch.")
                return null
            }

            ReelFrenLog.d("Client.get", "🚀 CF solved. Firing native OkHttp fetch to API...")
            val finalJson = try {
                spoofedApp.get(url, headers = headers(url, true), timeout = 25L).text
            } catch (e: Exception) { "" }
            
            if (finalJson.isNullOrEmpty() || ReelFrenCloudflare.isChallengeResponse(finalJson)) {
                ReelFrenLog.d("Client.get", "❌ Final OkHttp fetch failed. Giving up.")
                return null
            }
            
            ReelFrenLog.d("Client.get", "✅ API Fetch Succeeded! Length: ${finalJson.length}")
            return finalJson
        }
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
