package com.reel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.WebSettings
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
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

// ── JSON Parser ──────────────────────────────────────────────────────────────
object ReelFrenParse {

    fun homeItems(body: String): List<HomeItem> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: run {
            ReelFrenLog.d("Parse.homeItems", "Invalid JSON (len=${body.length})")
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
            else HomeItem(
                id = id,
                provider = clean(o, "provider"),
                title = title,
                cover = clean(o, "cover"),
                intro = clean(o, "intro"),
                episodes = o.optInt("episodes")
            )
        }
    }

    fun detail(body: String): DetailInfo? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("detail") ?: root.optJSONObject("data") ?: root
        val id = clean(o, "id")
        val title = clean(o, "title")
        if (id.isEmpty() || title.isEmpty()) return null

        val videos = mutableListOf<VideoEntry>()
        o.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                videos += VideoEntry(
                    episode = v.optInt("episode", i + 1),
                    duration = v.optInt("duration"),
                    vid = clean(v, "vid"),
                    available = v.optBoolean("available", true)
                )
            }
        }
        return DetailInfo(
            id = id,
            provider = clean(o, "provider"),
            title = title,
            cover = clean(o, "cover"),
            intro = clean(o, "intro"),
            episodes = o.optInt("episodes", videos.size),
            videos = videos.sortedBy { it.episode }
        )
    }

    fun playback(body: String): PlaybackInfo? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("data") ?: root.optJSONObject("playback") ?: root

        val qualities = mutableListOf<QualityEntry>()
        o.optJSONArray("qualityList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                val url = clean(q, "url")
                if (url.isEmpty()) continue
                qualities += QualityEntry(
                    label = clean(q, "label").ifEmpty { "Auto" },
                    url = url,
                    format = clean(q, "format")
                )
            }
        }
        val direct = clean(o, "videoUrl")
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            qualities.add(0, QualityEntry("Auto", direct, ReelFrenQuality.inferFormat(direct)))
        }
        if (qualities.isEmpty()) return null

        val subs = mutableListOf<SubtitleEntry>()
        o.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val u = clean(s, "url")
                if (u.isEmpty()) continue
                subs += SubtitleEntry(clean(s, "label"), clean(s, "srclang"), u)
            }
        }
        return PlaybackInfo(
            title = clean(o, "title"),
            episodeNumber = o.optInt("episodeNumber", 1),
            totalEpisodes = o.optInt("totalEpisodes", 1),
            locked = o.optBoolean("locked"),
            qualities = qualities,
            subtitles = subs,
            server = clean(o, "sourceServer").ifEmpty { "1" }
        )
    }

    private fun clean(o: JSONObject, key: String, fallback: String = ""): String {
        val v = o.optString(key, fallback).trim()
        return if (v.equals("null", ignoreCase = true)) fallback else v
    }
}

// ── Unified Persistence & State ──────────────────────────────────────────────
object ReelFrenStore {
    @Volatile var appContext: Context? = null
        private set
    @Volatile var defaultSystemUa: String = ReelFrenClient.FALLBACK_UA
        private set

    @Volatile private var storePrefs: SharedPreferences? = null
    @Volatile private var providerPrefs: SharedPreferences? = null

    private val memoryUa = ConcurrentHashMap<String, String>()
    private val memoryCookies = ConcurrentHashMap<String, String>()

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        storePrefs = app.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
        providerPrefs = app.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)

        runCatching {
            WebSettings.getDefaultUserAgent(app).replace("; wv", "").trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ua ->
            defaultSystemUa = ua
        }
        ReelFrenLog.d("Store", "init() defaultUa=${defaultSystemUa.take(70)}")
    }

    fun isProviderEnabled(slug: String): Boolean {
        val default = ReelFrenConfig.DEFAULT_ENABLED_SLUGS.contains(slug)
        return providerPrefs?.getBoolean("provider_$slug", default) ?: default
    }

    fun setProviderEnabled(editor: SharedPreferences.Editor, slug: String, enabled: Boolean) {
        editor.putBoolean("provider_$slug", enabled)
    }

    fun providerPrefsEditor(): SharedPreferences.Editor? = providerPrefs?.edit()

    fun enabledProviders(): Map<String, String> {
        return ReelFrenConfig.PROVIDERS_MAP.filter { (_, slug) ->
            isProviderEnabled(slug)
        }.ifEmpty { ReelFrenConfig.PROVIDERS_MAP }
    }

    fun apiBase(): String {
        val raw = storePrefs?.getString("api_base", ReelFrenConfig.DEFAULT_API_BASE).orEmpty()
        val clean = raw.trim().trimEnd('/')
        return if (clean.startsWith("https://") || clean.startsWith("http://")) {
            clean
        } else {
            ReelFrenConfig.DEFAULT_API_BASE
        }
    }

    fun cookie(host: String): String {
        if (host.isBlank()) return ""
        val norm = host.lowercase(Locale.ROOT)
        memoryCookies[norm]?.let { return it }
        val saved = storePrefs?.getString(key("cookie", norm), "").orEmpty()
        if (saved.isNotEmpty()) memoryCookies[norm] = saved
        return saved
    }

    fun saveCookie(host: String, cookie: String) {
        if (host.isBlank()) return
        val norm = host.lowercase(Locale.ROOT)
        if (cookie.isBlank()) {
            memoryCookies.remove(norm)
        } else {
            memoryCookies[norm] = cookie
        }
        storePrefs?.edit()?.putString(key("cookie", norm), cookie)?.apply()
        ReelFrenLog.d("Store", "saveCookie($norm) len=${cookie.length}")
    }

    fun userAgent(host: String): String {
        if (host.isBlank()) return defaultSystemUa
        val norm = host.lowercase(Locale.ROOT)
        memoryUa[norm]?.let { return it }
        val saved = storePrefs?.getString(key("ua", norm), null)?.takeIf { it.isNotBlank() }
        if (saved != null) {
            memoryUa[norm] = saved
            return saved
        }
        return defaultSystemUa
    }

    fun setUserAgent(host: String, ua: String) {
        if (host.isBlank() || ua.isBlank()) return
        val norm = host.lowercase(Locale.ROOT)
        memoryUa[norm] = ua
        storePrefs?.edit()?.putString(key("ua", norm), ua)?.apply()
    }

    fun shouldSkipPrewarm(): Boolean {
        val lastFail = storePrefs?.getLong("prewarm_last_fail", 0L) ?: 0L
        val failCount = storePrefs?.getInt("prewarm_fail_count", 0) ?: 0
        if (lastFail == 0L || failCount <= 0) return false
        val shift = (failCount - 1).coerceIn(0, 6)
        val cooldown = 60_000L * (1L shl shift) // 1m, 2m, 4m, 8m, 16m, 32m, 64m
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

    private fun key(type: String, host: String) = "${type}_$host"
}

// ── Quality & Stream Format Mapping ──────────────────────────────────────────
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

// ── URL Parsing ──────────────────────────────────────────────────────────────
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

// ── HTTP Client ──────────────────────────────────────────────────────────────
object ReelFrenClient {

    const val FALLBACK_UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val siteHost by lazy { ReelFrenCloudflare.hostOf(ReelFrenConfig.REEL_SITE) }

    private fun apiBase() = ReelFrenStore.apiBase()
    private fun query(v: String) = URLEncoder.encode(v, "UTF-8")

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
        val savedUa = ReelFrenStore.userAgent(host)

        val map = linkedMapOf(
            "User-Agent"      to savedUa,
            "Accept"          to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin"          to ReelFrenConfig.REEL_SITE,
            "Referer"         to "${ReelFrenConfig.REEL_SITE}/"
        )
        if (savedCookie.isNotBlank()) map["Cookie"] = savedCookie
        return map
    }

    fun posterHeaders(posterUrl: String?): Map<String, String>? {
        if (posterUrl.isNullOrEmpty()) return null
        val host = ReelFrenCloudflare.hostOf(posterUrl)
        return if (host == siteHost || host.endsWith(".$siteHost")) headers(posterUrl) else null
    }

    suspend fun get(url: String): String? {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val initialCookie = ReelFrenStore.cookie(targetHost)

        val firstResp = try {
            app.get(url, headers = headers(url), timeout = 20L)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Client.get", e)
            null
        }

        if (firstResp == null) {
            ReelFrenLog.d("Client.get", "Network failure (null response) for $url")
            return null
        }

        if (!ReelFrenCloudflare.isChallengeResponse(firstResp.code, firstResp.headers, firstResp.text)) {
            return firstResp.text
        }

        ReelFrenLog.d("Client.get", "Challenge detected (HTTP ${firstResp.code}, len=${firstResp.text.length}) on $url")

        val latestCookie = ReelFrenStore.cookie(targetHost)
        if (latestCookie.isNotBlank() && latestCookie != initialCookie && latestCookie.contains("cf_clearance")) {
            val quickRetry = try {
                app.get(url, headers = headers(url), timeout = 20L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (quickRetry != null && !ReelFrenCloudflare.isChallengeResponse(quickRetry.code, quickRetry.headers, quickRetry.text)) {
                return quickRetry.text
            }
        }

        val solveUrl = if (targetHost == siteHost) ReelFrenConfig.REEL_SITE else url

        val solved = ReelFrenCloudflare.solve(solveUrl, invalidatedCookie = initialCookie)
        if (!solved) return null

        val retryResp = try {
            app.get(url, headers = headers(url), timeout = 20L)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Client.get/retry", e)
            return null
        }

        if (ReelFrenCloudflare.isChallengeResponse(retryResp.code, retryResp.headers, retryResp.text)) {
            ReelFrenLog.d("Client.get", "Still challenged after solve -> forcing interactive dialog")
            val interactiveSolved = ReelFrenCloudflare.solve(
                solveUrl,
                invalidatedCookie = ReelFrenStore.cookie(targetHost),
                forceInteractive = true
            )
            if (!interactiveSolved) return null

            val finalResp = try {
                app.get(url, headers = headers(url), timeout = 20L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                return null
            }
            return if (ReelFrenCloudflare.isChallengeResponse(finalResp.code, finalResp.headers, finalResp.text)) {
                null
            } else {
                finalResp.text
            }
        }

        return retryResp.text
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
