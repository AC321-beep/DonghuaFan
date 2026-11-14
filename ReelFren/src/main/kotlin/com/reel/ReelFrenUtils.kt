package com.reel

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

// ── Debug Logger ─────────────────────────────────────────────────────────────
object ReelFrenDebugLog {
    private const val TAG = "ReelFren/CF"
    fun d(scope: String, msg: String) {
        android.util.Log.e(TAG, "[$scope][${Thread.currentThread().name}] $msg")
    }
    fun t(scope: String, e: Throwable) {
        android.util.Log.e(TAG, "[$scope][${Thread.currentThread().name}] THROWABLE: ${e.message}", e)
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

// ── Config ───────────────────────────────────────────────────────────────────
object ReelFrenConfig {
    const val REEL_SITE = "https://reelfren.net"
    const val DEFAULT_API_BASE = "https://reelfren.net"
}

// ── JSON parser ──────────────────────────────────────────────────────────────
object ReelFrenParse {

    fun homeItems(body: String): List<HomeItem> {
        ReelFrenDebugLog.d("Parse.homeItems", "Parsing body len=${body.length}")
        val root = runCatching { JSONObject(body) }.getOrNull() ?: run {
            ReelFrenDebugLog.d("Parse.homeItems", "JSONObject failed -> return emptyList")
            return emptyList()
        }
        val arr = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: run {
                ReelFrenDebugLog.d("Parse.homeItems", "No data array/items found -> return emptyList")
                return emptyList()
            }
        val result = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = clean(o, "id"); val title = clean(o, "title")
            if (id.isEmpty() || title.isEmpty()) null
            else HomeItem(id, clean(o, "provider"), title, clean(o, "cover"),
                          clean(o, "intro"), o.optInt("episodes"))
        }
        ReelFrenDebugLog.d("Parse.homeItems", "Parsed ${result.size} items")
        return result
    }

    fun detail(body: String): DetailInfo? {
        ReelFrenDebugLog.d("Parse.detail", "Parsing body len=${body.length}")
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("detail") ?: root.optJSONObject("data") ?: root
        val id = clean(o, "id"); val title = clean(o, "title")
        if (id.isEmpty() || title.isEmpty()) return null

        val videos = mutableListOf<VideoEntry>()
        o.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                videos += VideoEntry(
                    v.optInt("episode", i + 1), v.optInt("duration"),
                    clean(v, "vid"), v.optBoolean("available", true)
                )
            }
        }
        return DetailInfo(id, clean(o, "provider"), title, clean(o, "cover"),
            clean(o, "intro"), o.optInt("episodes", videos.size),
            videos.sortedBy { it.episode })
    }

    fun playback(body: String): PlaybackInfo? {
        ReelFrenDebugLog.d("Parse.playback", "Parsing body len=${body.length}")
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("data") ?: root.optJSONObject("playback") ?: root

        val qualities = mutableListOf<QualityEntry>()
        o.optJSONArray("qualityList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                val url = clean(q, "url"); if (url.isEmpty()) continue
                qualities += QualityEntry(
                    clean(q, "label").ifEmpty { "Auto" }, url, clean(q, "format"))
            }
        }
        val direct = clean(o, "videoUrl")
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            val fmt = when {
                direct.contains(".m3u8", true) -> "hls"
                direct.contains(".mpd",  true) -> "dash"
                else -> "video"
            }
            qualities.add(0, QualityEntry("Auto", direct, fmt))
        }
        if (qualities.isEmpty()) {
            ReelFrenDebugLog.d("Parse.playback", "No qualities found -> return null")
            return null
        }

        val subs = mutableListOf<SubtitleEntry>()
        o.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val u = clean(s, "url"); if (u.isEmpty()) continue
                subs += SubtitleEntry(clean(s, "label"), clean(s, "srclang"), u)
            }
        }
        return PlaybackInfo(
            clean(o, "title"), o.optInt("episodeNumber", 1),
            o.optInt("totalEpisodes", 1), o.optBoolean("locked"),
            qualities, subs, clean(o, "sourceServer").ifEmpty { "1" }
        )
    }

    private fun clean(o: JSONObject, key: String, fallback: String = ""): String {
        val v = o.optString(key, fallback).trim()
        return if (v == "null") fallback else v
    }
}

// ── Persistence ──────────────────────────────────────────────────────────────
object ReelFrenStore {
    @Volatile private var prefs: SharedPreferences? = null
    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
        ReelFrenDebugLog.d("Store", "init() called")
    }

    fun apiBase(): String {
        val raw = prefs?.getString("api_base", ReelFrenConfig.DEFAULT_API_BASE).orEmpty()
        val clean = raw.trim().trimEnd('/')
        val base = if (clean.startsWith("https://") || clean.startsWith("http://"))
            clean else ReelFrenConfig.DEFAULT_API_BASE
        ReelFrenDebugLog.d("Store", "apiBase() -> $base")
        return base
    }
    fun setApiBase(v: String) {
        val clean = v.trim().trimEnd('/')
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) return
        prefs?.edit()?.putString("api_base", clean)?.apply()
    }

    fun cookie(host: String): String {
        val c = if (host.isBlank()) "" else prefs?.getString(key("cookie", host), "").orEmpty()
        ReelFrenDebugLog.d("Store", "cookie($host) -> len=${c.length} hasCf=${c.contains("cf_clearance")}")
        return c
    }
    
    fun saveCookie(host: String, cookie: String) {
        if (host.isBlank() || cookie.isBlank()) return
        ReelFrenDebugLog.d("Store", "saveCookie($host) -> len=${cookie.length}")
        prefs?.edit()?.putString(key("cookie", host), cookie)?.apply()
    }

    fun setUserAgent(host: String, ua: String) {
        if (host.isBlank() || ua.isBlank()) return
        prefs?.edit()?.putString(key("ua", host), ua)?.apply()
    }
    fun userAgent(host: String): String? {
        val ua = prefs?.getString(key("ua", host), null)
        ReelFrenDebugLog.d("Store", "userAgent($host) -> ${if (ua == null) "null" else "len=${ua.length}"}")
        return ua
    }

    private fun key(type: String, host: String) = "${type}_${host.lowercase(Locale.ROOT)}"
}

// ── Quality mapping ──────────────────────────────────────────────────────────
object ReelFrenQuality {
    private val heavy = Regex("(?i)\\b(h\\.?26[56]|hevc|x\\.?26[56]|av1)\\b")

    fun label(raw: String) = raw.trim().ifEmpty { "Auto" }

    fun of(label: String): Int {
        val lower = label.lowercase(Locale.ROOT)
        val match = Regex("(?i)(\\d{3,4})\\s*p\\b").find(label)
        val base = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: when {
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
        q >= 2160 -> 1080; q >= 1080 -> 720; q >= 720 -> 480; else -> 360
    }
}

// ── URL parsing ──────────────────────────────────────────────────────────────
object ReelFrenUrl {
    fun page(slug: String, id: String) = "${ReelFrenConfig.REEL_SITE}/drama/$slug/$id"
    fun episode(slug: String, id: String, ep: Int) = "${ReelFrenConfig.REEL_SITE}/watch/$slug/$id?ep=$ep"

    fun parse(raw: String?): Triple<String, String, Int> {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return Triple("", "", 1)

        Regex("/(?:drama|watch)/([^/?#|]+)/([^/?#|]+)").find(input)?.let { m ->
            val slug = m.groupValues[1].trim().lowercase(Locale.ROOT)
            val id   = m.groupValues[2].trim()
            val ep   = Regex("[?&]ep=(\\d+)").find(input)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            if (slug.isNotEmpty() && id.isNotEmpty()) return Triple(slug, id, ep)
        }

        val body = input.substringAfter("://", input)
            .substringAfter("/", "").substringBefore("?").substringBefore("#")
        val parts = body.split("|")
        val slug = parts.getOrNull(0)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id   = parts.getOrNull(1)?.trim().orEmpty()
        val ep   = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
        return if (slug.isNotEmpty() && id.isNotEmpty()) Triple(slug, id, ep)
        else Triple("", "", 1)
    }
}

// ── HTTP client ──────────────────────────────────────────────────────────────
object ReelFrenClient {

    const val UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val cfKiller by lazy { CloudflareKiller() }

    fun apiBase() = ReelFrenStore.apiBase()
    fun query(v: String) = URLEncoder.encode(v, "UTF-8")

    fun homeUrl(slug: String, category: String = "", page: Int = 1): String {
        val p = mutableListOf("provider=${query(slug)}", "lang=en")
        if (category.isNotEmpty()) p += "category=${query(category)}"
        val off = page - 1
        if (off > 0) p += "offset=$off"
        return "${apiBase()}/api/home?${p.joinToString("&")}"
    }
    fun detailUrl(slug: String, id: String) =
        "${apiBase()}/api/detail?provider=${query(slug)}&id=${query(id)}"
    fun videoUrl(slug: String, id: String, ep: Int) =
        "${apiBase()}/api/video?provider=${query(slug)}&id=${query(id)}&ep=$ep"
    fun searchUrl(slug: String, term: String) =
        "${apiBase()}/api/search?q=${query(term)}&provider=${query(slug)}"

    fun headers(url: String): Map<String, String> {
        val host = ReelFrenCloudflare.hostOf(url)
        val savedCookie = ReelFrenStore.cookie(host)
        val savedUa = ReelFrenStore.userAgent(host) ?: UA

        val headers = linkedMapOf(
            "User-Agent"      to savedUa,
            "Accept"          to "application/json, text/html, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin"          to ReelFrenConfig.REEL_SITE,
            "Referer"         to "${ReelFrenConfig.REEL_SITE}/"
        )
        if (savedCookie.isNotBlank()) headers["Cookie"] = savedCookie
        return headers
    }

    suspend fun get(url: String): String? {
        ReelFrenDebugLog.d("Client.get", "ENTRY url=$url")
        
        ReelFrenDebugLog.d("Client.get", "Starting first attempt WITH cfKiller...")
        val first = runCatching {
            app.get(url, headers = headers(url), interceptor = cfKiller).text
        }.onFailure { 
            ReelFrenDebugLog.t("Client.get", it) 
        }.getOrNull()

        ReelFrenDebugLog.d("Client.get", "First attempt returned. bodyLen=${first?.length} isChallenge=${first?.let { ReelFrenCloudflare.isChallengeBody(it) }}")

        if (first != null && !ReelFrenCloudflare.isChallengeBody(first)) {
            ReelFrenDebugLog.d("Client.get", "First attempt is NOT a challenge. Returning success.")
            return first
        }

        val targetHost = ReelFrenCloudflare.hostOf(url)
        val solveUrl = if (targetHost == ReelFrenCloudflare.hostOf(ReelFrenConfig.REEL_SITE))
            ReelFrenConfig.REEL_SITE
        else url

        ReelFrenDebugLog.d("Client.get", "Calling ReelFrenCloudflare.solve(solveUrl=$solveUrl)...")
        val solved = ReelFrenCloudflare.solve(solveUrl)
        ReelFrenDebugLog.d("Client.get", "solve() returned $solved")

        if (!solved) {
            ReelFrenDebugLog.d("Client.get", "solve() failed. Returning null.")
            return null
        }

        ReelFrenDebugLog.d("Client.get", "Starting retry attempt (NO cfKiller)...")
        val retry = runCatching { 
            app.get(url, headers = headers(url)).text 
        }.onFailure { 
            ReelFrenDebugLog.t("Client.get", it) 
        }.getOrNull()
        
        ReelFrenDebugLog.d("Client.get", "Retry returned. bodyLen=${retry?.length} isChallenge=${retry?.let { ReelFrenCloudflare.isChallengeBody(it) }}")
        return retry
    }

    suspend fun home(slug: String, category: String = "", page: Int = 1): List<HomeItem> {
        ReelFrenDebugLog.d("Client.home", "ENTRY slug=$slug page=$page")
        val body = get(homeUrl(slug, category, page))
        ReelFrenDebugLog.d("Client.home", "get() returned bodyLen=${body?.length}")
        return body?.let(ReelFrenParse::homeItems) ?: emptyList()
    }

    suspend fun detail(slug: String, id: String): DetailInfo? {
        ReelFrenDebugLog.d("Client.detail", "ENTRY slug=$slug id=$id")
        val body = get(detailUrl(slug, id))
        return body?.let(ReelFrenParse::detail)
    }

    suspend fun playback(slug: String, id: String, ep: Int): PlaybackInfo? {
        ReelFrenDebugLog.d("Client.playback", "ENTRY slug=$slug id=$id ep=$ep")
        val body = get(videoUrl(slug, id, ep))
        return body?.let(ReelFrenParse::playback)
    }

    suspend fun search(slug: String, term: String): List<HomeItem> {
        ReelFrenDebugLog.d("Client.search", "ENTRY slug=$slug term=$term")
        val body = get(searchUrl(slug, term))
        return body?.let(ReelFrenParse::homeItems) ?: emptyList()
    }

    fun absUrl(value: String): String {
        val clean = value.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("https://", true) || clean.startsWith("http://", true)) return clean
        if (clean.startsWith("//")) return "https:$clean"
        val base = apiBase()
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }
}
