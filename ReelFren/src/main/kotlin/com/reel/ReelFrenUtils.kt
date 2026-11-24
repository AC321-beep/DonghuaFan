package com.reel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ----------------------------------------------------------------------
// DATA MODELS
// ----------------------------------------------------------------------
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

// ----------------------------------------------------------------------
// LOGGER
// ----------------------------------------------------------------------
object ReelFrenLog {
    private const val TAG = "ReelFren/CF"
    var isVerbose: Boolean = false 
    
    fun d(scope: String, msg: String) {
        if (!isVerbose) return
        Log.e(TAG, "[$scope][${Thread.currentThread().name}] $msg")
    }

    fun t(scope: String, e: Throwable) {
        if (!isVerbose) return
        Log.e(TAG, "[$scope][${Thread.currentThread().name}] ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

// ----------------------------------------------------------------------
// CONFIG
// ----------------------------------------------------------------------
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

// ----------------------------------------------------------------------
// NETWORK CLIENT
// ----------------------------------------------------------------------
class WebViewCookieJar : CookieJar {
    private val cookieManager = CookieManager.getInstance()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()
        for (cookie in cookies) cookieManager.setCookie(urlString, cookie.toString())
        cookieManager.flush()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieStr = cookieManager.getCookie(url.toString())
        if (cookieStr.isNullOrEmpty()) return emptyList()
        return cookieStr.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

object ReelFrenClient {
    const val GLOBAL_MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    
    private val apiHost by lazy { ReelFrenCloudflare.hostOf(ReelFrenConfig.DEFAULT_API_BASE) }
    private val apiMutex = Mutex()
    
    private val chromeSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        .build()
        
    private val spoofedClient = app.baseClient.newBuilder().cookieJar(WebViewCookieJar())
        .connectionSpecs(listOf(chromeSpec, ConnectionSpec.CLEARTEXT))
        .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2)).build()
    private val spoofedApp = com.lagradost.nicehttp.Requests(spoofedClient)

    private fun apiBase() = ReelFrenStore.apiBase()
    private fun query(v: String) = URLEncoder.encode(v, "UTF-8")
    
    fun homeUrl(slug: String, category: String = "", page: Int = 1): String {
        val p = mutableListOf("lang=en", "page=$page")
        if (slug != "all_dramas" && slug.isNotEmpty()) p += "provider=${query(slug)}"
        if (category.isNotEmpty()) p += "category=${query(category)}"
        val offset = (page - 1) * 20
        if (offset > 0) p += "offset=$offset"
        return "${apiBase()}/api/home?${p.joinToString("&")}"
    }

    fun detailUrl(slug: String, id: String) = "${apiBase()}/api/detail?provider=${query(slug)}&id=${query(id)}&lang=en"
    fun videoUrl(slug: String, id: String, ep: Int, server: Int = 1) = "${apiBase()}/api/video?provider=${query(slug)}&id=${query(id)}&ep=$ep&server=$server"
    
    fun searchUrl(slug: String, term: String): String {
        val p = mutableListOf("q=${query(term)}", "lang=en")
        if (slug != "all_dramas" && slug.isNotEmpty()) p += "provider=${query(slug)}"
        return "${apiBase()}/api/search?${p.joinToString("&")}"
    }

    fun headers(url: String, isApiCall: Boolean = false): Map<String, String> {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val map = linkedMapOf<String, String>()
        map["User-Agent"] = GLOBAL_MOBILE_UA
        map["Accept-Language"] = "en-US,en;q=0.9"
        map["Origin"] = ReelFrenConfig.REEL_SITE
        map["Referer"] = "${ReelFrenConfig.REEL_SITE}/"
        if (isApiCall) map["Accept"] = "application/json, text/plain, */*" else map["Accept"] = "*/*"
        if (targetHost == apiHost || targetHost.contains("reelfren.net")) {
            val cookie = ReelFrenStore.cookie(targetHost)
            if (cookie.isNotBlank()) map["Cookie"] = cookie
        }
        return map
    }

    fun videoHeaders(url: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        map["User-Agent"] = GLOBAL_MOBILE_UA 
        map["Accept"] = "*/*"
        map["Connection"] = "keep-alive"
        map["Origin"] = ReelFrenConfig.REEL_SITE
        map["Referer"] = "${ReelFrenConfig.REEL_SITE}/"
        return map
    }

    fun posterHeaders(posterUrl: String?): Map<String, String>? {
        if (posterUrl.isNullOrEmpty()) return null
        return headers(posterUrl, isApiCall = false)
    }

    suspend fun get(url: String): String? {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val isReelFrenApi = targetHost == apiHost
        
        if (!isReelFrenApi) return try { app.get(url, headers = headers(url, false), timeout = 25L).text } catch (e: Exception) { null }

        ReelFrenLog.d("Client.get", "Fetching API: $url")
        var jsonText = try { 
            spoofedApp.get(url, headers = headers(url, true), timeout = 25L).text 
        } catch (e: Exception) { 
            ReelFrenLog.t("Client.get", e) 
            "" 
        }
        
        if (jsonText.isNotEmpty() && !ReelFrenCloudflare.isChallengeResponse(jsonText)) return jsonText

        ReelFrenLog.d("Client.get", "Falling back to CF Mutex...")
        apiMutex.withLock {
            jsonText = try { spoofedApp.get(url, headers = headers(url, true), timeout = 25L).text } catch (e: Exception) { "" }
            if (jsonText.isNotEmpty() && !ReelFrenCloudflare.isChallengeResponse(jsonText)) return jsonText

            if (!ReelFrenCloudflare.solve(ReelFrenConfig.REEL_SITE)) return null
            val finalJson = try { spoofedApp.get(url, headers = headers(url, true), timeout = 25L).text } catch (e: Exception) { 
                ReelFrenLog.t("Client.get", e)
                "" 
            }
            if (finalJson.isNullOrEmpty() || ReelFrenCloudflare.isChallengeResponse(finalJson)) return null
            return finalJson
        }
    }

    suspend fun home(slug: String, page: Int = 1, category: String = ""): List<HomeItem> = get(homeUrl(slug, category, page))?.let(ReelFrenParse::homeItems) ?: emptyList()
    
    // FIX: Pass the slug and id down to the parser so it can fallback to them if the JSON is incomplete
    suspend fun detail(slug: String, id: String): DetailInfo? = get(detailUrl(slug, id))?.let { ReelFrenParse.detail(it, slug, id) }
    
    suspend fun search(slug: String, term: String): List<HomeItem> = get(searchUrl(slug, term))?.let(ReelFrenParse::homeItems) ?: emptyList()
    
    suspend fun playback(slug: String, id: String, ep: Int, serverId: Int): PlaybackInfo? {
        val jsonText = get(videoUrl(slug, id, ep, serverId)) ?: return null
        return ReelFrenParse.playback(jsonText, serverId)
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

// ----------------------------------------------------------------------
// PARSERS
// ----------------------------------------------------------------------
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
            // Fallback for messy APIs
            val id = clean(o, "id").ifEmpty { clean(o, "dramaId") }
            val title = clean(o, "title").ifEmpty { clean(o, "name") }
            
            if (id.isEmpty() || title.isEmpty()) null
            else HomeItem(id = id, provider = clean(o, "provider"), title = title, cover = clean(o, "cover"), intro = clean(o, "intro"), episodes = o.optInt("episodes"))
        }
    }

    // FIX: Accepts fallbackSlug and fallbackId 
    fun detail(body: String, fallbackSlug: String, fallbackId: String): DetailInfo? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val o = root.optJSONObject("detail") ?: root.optJSONObject("data") ?: root
        
        // FIX: If the API omits the 'id' field in the JSON (happens with string slugs), we use the one we requested with.
        val id = clean(o, "id").ifEmpty { clean(o, "dramaId") }.ifEmpty { fallbackId }
        val title = clean(o, "title").ifEmpty { clean(o, "name") }
        
        if (id.isEmpty() || title.isEmpty()) {
            ReelFrenLog.d("Parse.detail", "Failed parsing detail. Missing ID or Title in JSON. Fallback ID: $fallbackId")
            return null
        }
        
        val provider = clean(o, "provider").ifEmpty { fallbackSlug }
        
        val videos = mutableListOf<VideoEntry>()
        o.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                videos += VideoEntry(episode = v.optInt("episode", i + 1), duration = v.optInt("duration"), vid = clean(v, "vid"), available = v.optBoolean("available", true))
            }
        }
        
        return DetailInfo(
            id = id, 
            provider = provider, 
            title = title, 
            cover = clean(o, "cover"), 
            intro = clean(o, "intro").ifEmpty { clean(o, "description") }, 
            episodes = o.optInt("episodes", videos.size), 
            videos = videos.sortedBy { it.episode }
        )
    }

    fun playback(body: String, requestedServerId: Int): PlaybackInfo? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val dataNode = root.optJSONObject("data") ?: root.optJSONObject("playback") ?: root

        val qualities = mutableListOf<QualityEntry>()
        val subs = mutableListOf<SubtitleEntry>()

        val qArr = dataNode.optJSONArray("qualityList") ?: dataNode.optJSONArray("qualities") ?: dataNode.optJSONArray("sources") 
                   ?: dataNode.optJSONArray("videos") ?: dataNode.optJSONArray("list") ?: dataNode.optJSONArray("streams")
        
        if (qArr == null || qArr.length() == 0) return null

        for (i in 0 until qArr.length()) {
            val q = qArr.optJSONObject(i) ?: continue
            val url = firstNonEmpty(q, "url", "src", "file", "videoUrl")
            if (url.isNotEmpty()) {
                qualities += QualityEntry(label = firstNonEmpty(q, "label", "quality", "name", "resolution").ifEmpty { "Auto" }, url = url, format = clean(q, "format"))
            }
            collectSubtitlesFromNode(q, subs)
        }

        val direct = firstNonEmpty(dataNode, "videoUrl", "url", "streamUrl", "playUrl", "m3u8", "src")
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            qualities.add(0, QualityEntry("Auto", direct, ReelFrenQuality.inferFormat(direct)))
        }
        if (qualities.isEmpty()) return null

        collectSubtitlesFromNode(dataNode, subs)
        if (dataNode !== root) collectSubtitlesFromNode(root, subs)

        return PlaybackInfo(
            title = clean(dataNode, "title").ifEmpty { clean(dataNode, "name") },
            episodeNumber = dataNode.optInt("episodeNumber", 1),
            totalEpisodes = dataNode.optInt("totalEpisodes", 1),
            locked = dataNode.optBoolean("locked"),
            qualities = qualities,
            subtitles = subs.distinctBy { "${it.lang.lowercase(Locale.ROOT)}|${it.url}" },
            server = requestedServerId.toString()
        )
    }

    private fun collectSubtitlesFromNode(node: JSONObject, out: MutableList<SubtitleEntry>) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lowerKey = key.lowercase(Locale.ROOT)
            if (lowerKey.contains("sub") || lowerKey.contains("track") || lowerKey.contains("caption")) {
                node.optJSONArray(key)?.let { arr -> parseSubtitleArray(arr, out) }
                node.optJSONObject(key)?.let { obj -> parseSubtitleObjectOrMap(obj, out) }
            }
        }
        for (key in SUB_DIRECT_KEYS) {
            if (node.optJSONObject(key) != null || node.optJSONArray(key) != null) continue
            val directUrl = clean(node, key)
            if (looksLikeSubtitleUrl(directUrl)) {
                val fallbackLang = firstNonEmpty(node, "lang", "language", "srclang").ifEmpty { "English" }
                out += SubtitleEntry(label = clean(node, "subtitleLabel").ifEmpty { fallbackLang }, lang = fallbackLang, url = directUrl)
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
                    out += SubtitleEntry(label = if (guessedLang == "und") "Subtitle ${i + 1}" else guessedLang.uppercase(Locale.ROOT), lang = guessedLang, url = rawUrl)
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

    private fun looksLikeSubtitleUrl(u: String): Boo
