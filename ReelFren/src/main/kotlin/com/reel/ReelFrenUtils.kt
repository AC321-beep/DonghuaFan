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
    
    @Volatile private var currentApiVersion = "v21"
    
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
        val p = mutableListOf("lang=en", "cv=$currentApiVersion", "page=$page")
        if (slug != "all_dramas" && slug.isNotEmpty()) p += "provider=${query(slug)}"
        if (category.isNotEmpty()) p += "category=${query(category)}"
        val offset = (page - 1) * 20
        if (offset > 0) p += "offset=$offset"
        return "${apiBase()}/api/home?${p.joinToString("&")}"
    }

    fun detailApiUrl(slug: String, id: String) = "${apiBase()}/api/detail?provider=${query(slug)}&id=${query(id)}&lang=en&cv=$currentApiVersion"
    fun detailHtmlUrl(slug: String, id: String) = "${ReelFrenConfig.REEL_SITE}/drama/${query(slug)}/${query(id)}?lang=en"
    
    fun videoUrl(slug: String, id: String, ep: Int, server: Int = 1) = "${apiBase()}/api/video?provider=${query(slug)}&id=${query(id)}&ep=$ep&lang=en&server=$server&cv=$currentApiVersion"
    
    fun searchUrl(slug: String, term: String): String {
        val p = mutableListOf("q=${query(term)}", "lang=en", "cv=$currentApiVersion")
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
        if (isApiCall) map["Accept"] = "application/json, text/plain, */*" else map["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
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

    private fun isVersionError(json: String): Boolean {
        if (json.isEmpty()) return false
        val lower = json.lowercase(Locale.ROOT)
        return lower.contains("version") && (lower.contains("update") || lower.contains("invalid") || lower.contains("deprecated"))
    }

    suspend fun get(url: String, isApiCall: Boolean = true): String? {
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val isReelFrenApi = targetHost == apiHost || targetHost.contains("reelfren.net")
        
        if (!isReelFrenApi) return try { app.get(url, headers = headers(url, false), timeout = 25L).text } catch (e: Exception) { null }

        ReelFrenLog.d("Client.get", "Fetching: $url (isApiCall=$isApiCall)")
        var responseText = try { 
            spoofedApp.get(url, headers = headers(url, isApiCall), timeout = 25L).text 
        } catch (e: Exception) { 
            ReelFrenLog.t("Client.get", e) 
            "" 
        }
        
        if (isApiCall && isVersionError(responseText)) {
            ReelFrenLog.d("Client.get", "Version error detected on $currentApiVersion. Attempting generic fallback...")
            val newUrl = url.replace("cv=$currentApiVersion", "")
            responseText = try { spoofedApp.get(newUrl, headers = headers(newUrl, true), timeout = 25L).text } catch (e: Exception) { "" }
        }
        
        if (responseText.isNotEmpty() && !ReelFrenCloudflare.isChallengeResponse(responseText)) return responseText

        ReelFrenLog.d("Client.get", "Falling back to CF Mutex...")
        apiMutex.withLock {
            responseText = try { spoofedApp.get(url, headers = headers(url, isApiCall), timeout = 25L).text } catch (e: Exception) { "" }
            if (responseText.isNotEmpty() && !ReelFrenCloudflare.isChallengeResponse(responseText)) return responseText

            if (!ReelFrenCloudflare.solve(ReelFrenConfig.REEL_SITE)) return null
            val finalResponse = try { spoofedApp.get(url, headers = headers(url, isApiCall), timeout = 25L).text } catch (e: Exception) { 
                ReelFrenLog.t("Client.get", e)
                "" 
            }
            if (finalResponse.isNullOrEmpty() || ReelFrenCloudflare.isChallengeResponse(finalResponse)) return null
            return finalResponse
        }
    }

    suspend fun home(slug: String, page: Int = 1, category: String = ""): List<HomeItem> = get(homeUrl(slug, category, page), isApiCall = true)?.let(ReelFrenParse::homeItems) ?: emptyList()
    
    suspend fun detail(slug: String, id: String): DetailInfo? {
        val apiUrl = detailApiUrl(slug, id)
        val apiJson = get(apiUrl, isApiCall = true)
        
        if (!apiJson.isNullOrEmpty()) {
            val apiResult = ReelFrenParse.detailApi(apiJson, slug, id)
            if (apiResult != null && apiResult.title.isNotEmpty()) {
                ReelFrenLog.d("Client.detail", "API parse successful.")
                return apiResult
            }
        }
        
        ReelFrenLog.d("Client.detail", "API parse failed or empty. Falling back to indestructible HTML scraper.")
        val htmlUrl = detailHtmlUrl(slug, id)
        val htmlStr = get(htmlUrl, isApiCall = false) ?: return null
        return ReelFrenParse.detailHtml(htmlStr, slug, id)
    }
    
    suspend fun search(slug: String, term: String): List<HomeItem> = get(searchUrl(slug, term), isApiCall = true)?.let(ReelFrenParse::homeItems) ?: emptyList()
    
    suspend fun playback(slug: String, id: String, ep: Int, serverId: Int): PlaybackInfo? {
        val jsonText = get(videoUrl(slug, id, ep, serverId), isApiCall = true) ?: return null
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

    private fun findValueRecursively(obj: JSONObject, vararg keysToFind: String): String {
        for (key in keysToFind) {
            val v = clean(obj, key)
            if (v.isNotEmpty()) return v
        }

        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            val child = obj.optJSONObject(key)
            if (child != null) {
                val found = findValueRecursively(child, *keysToFind)
                if (found.isNotEmpty()) return found
            } else {
                val childArray = obj.optJSONArray(key)
                if (childArray != null) {
                    for (i in 0 until childArray.length()) {
                        val arrayObj = childArray.optJSONObject(i)
                        if (arrayObj != null) {
                            val found = findValueRecursively(arrayObj, *keysToFind)
                            if (found.isNotEmpty()) return found
                        }
                    }
                }
            }
        }
        return ""
    }

    private fun findArrayRecursively(obj: JSONObject, vararg keysToFind: String): JSONArray? {
        for (key in keysToFind) {
            val arr = obj.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }

        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            val child = obj.optJSONObject(key)
            if (child != null) {
                val found = findArrayRecursively(child, *keysToFind)
                if (found != null) return found
            }
        }
        return null
    }
    
    // CRITICAL FIX: Explicitly searches for the "detail" node generated by Next.js SSR inside the payload tree
    private fun findNestedDetailObject(obj: JSONObject): JSONObject? {
        if (obj.has("detail")) {
            val d = obj.optJSONObject("detail")
            if (d != null && d.length() > 0) return d
        }
        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            val child = obj.optJSONObject(key)
            if (child != null) {
                val found = findNestedDetailObject(child)
                if (found != null) return found
            } else {
                val childArray = obj.optJSONArray(key)
                if (childArray != null) {
                    for (i in 0 until childArray.length()) {
                        val arrayObj = childArray.optJSONObject(i)
                        if (arrayObj != null) {
                            val found = findNestedDetailObject(arrayObj)
                            if (found != null) return found
                        }
                    }
                }
            }
        }
        return null
    }

    fun homeItems(body: String): List<HomeItem> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = findArrayRecursively(root, "data", "items", "list", "results") ?: return emptyList()

        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = clean(o, "id").ifEmpty { clean(o, "dramaId") }
            val title = clean(o, "title").ifEmpty { clean(o, "name") }
            
            if (id.isEmpty() || title.isEmpty()) null
            else HomeItem(id = id, provider = clean(o, "provider"), title = title, cover = clean(o, "cover"), intro = clean(o, "intro"), episodes = o.optInt("episodes"))
        }
    }

    fun detailApi(body: String, fallbackSlug: String, fallbackId: String): DetailInfo? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        
        val id = findValueRecursively(root, "id", "dramaId").ifEmpty { fallbackId }
        val title = findValueRecursively(root, "title", "name", "dramaName")
        
        if (title.isEmpty()) return null
        
        val provider = findValueRecursively(root, "provider").ifEmpty { fallbackSlug }
        val cover = findValueRecursively(root, "cover", "poster", "imageUrl")
        val intro = findValueRecursively(root, "intro", "description", "summary")
        
        val videos = mutableListOf<VideoEntry>()
        val vArr = findArrayRecursively(root, "videos", "episodes", "list")
        
        vArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                
                val vid = clean(v, "vid").ifEmpty { clean(v, "id") }.ifEmpty { clean(v, "videoId") }
                if (vid.isEmpty()) continue
                
                videos += VideoEntry(
                    episode = v.optInt("episode", v.optInt("ep", i + 1)), 
                    duration = v.optInt("duration", v.optInt("length")), 
                    vid = vid, 
                    available = v.optBoolean("available", v.optBoolean("is_free", true))
                )
            }
        }
        
        val totalEps = findValueRecursively(root, "episodes", "total_episodes").toIntOrNull() ?: videos.size

        return DetailInfo(id, provider, title, cover, intro, totalEps, videos.sortedBy { it.episode })
    }

    // CRITICAL FIX: Extracts the entire __next_f.push string sequence from HTML and relies on native JSONObject parsing
    fun detailHtml(htmlBody: String, fallbackSlug: String, fallbackId: String): DetailInfo? {
        if (htmlBody.isBlank()) return null
        
        val nextPushRegex = Regex("""__next_f\.push\(\[(.*?)\]\)""")
        val matches = nextPushRegex.findAll(htmlBody)
        
        var targetRoot: JSONObject? = null
        
        for (match in matches) {
            val innerArrayStr = "[${match.groupValues[1]}]"
            try {
                val parsedArray = JSONArray(innerArrayStr)
                if (parsedArray.length() > 1 && parsedArray.get(1) is String) {
                    val rawStr = parsedArray.getString(1)
                    if (rawStr.contains("\"detail\"") || rawStr.contains("\\\"detail\\\"")) {
                        // The string itself might be double JSON encoded or just a raw string
                        val unescaped = rawStr.replace("\\\"", "\"").replace("\\\\", "\\")
                        try {
                            // Let's attempt to parse it directly
                            val jsonAttempt = JSONObject(unescaped)
                            val foundDetail = findNestedDetailObject(jsonAttempt)
                            if (foundDetail != null) {
                                targetRoot = foundDetail
                                break
                            }
                        } catch (e: Exception) {
                            // If it fails, maybe it's nested inside an array structure like the one in your HTML snippet
                            val cleanRootMatch = Regex("""\{.*\}""").find(unescaped)
                            if (cleanRootMatch != null) {
                                val cleanAttempt = JSONObject(cleanRootMatch.value)
                                val foundDetail = findNestedDetailObject(cleanAttempt)
                                if (foundDetail != null) {
                                    targetRoot = foundDetail
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Keep searching other __next_f script blocks
            }
        }

        if (targetRoot == null) {
            ReelFrenLog.d("Parse.detailHtml", "Failed to extract Next.js detail object from HTML arrays.")
            return null
        }
        
        val id = findValueRecursively(targetRoot, "id", "dramaId").ifEmpty { fallbackId }
        val title = findValueRecursively(targetRoot, "title", "name", "dramaName")
        
        if (title.isEmpty()) return null
        
        val provider = findValueRecursively(targetRoot, "provider").ifEmpty { fallbackSlug }
        val cover = findValueRecursively(targetRoot, "cover", "poster", "imageUrl")
        val intro = findValueRecursively(targetRoot, "intro", "description", "summary")
        
        val videos = mutableListOf<VideoEntry>()
        val vArr = findArrayRecursively(targetRoot, "videos", "episodes", "list")
        
        vArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                
                val vid = clean(v, "vid").ifEmpty { clean(v, "id") }.ifEmpty { clean(v, "videoId") }
                if (vid.isEmpty()) continue
                
                videos += VideoEntry(
                    episode = v.optInt("episode", v.optInt("ep", i + 1)), 
                    duration = v.optInt("duration", v.optInt("length")), 
                    vid = vid, 
                    available = v.optBoolean("available", v.optBoolean("is_free", true))
                )
            }
        }
        
        val totalEps = findValueRecursively(targetRoot, "episodes", "total_episodes").toIntOrNull() ?: videos.size

        return DetailInfo(id, provider, title, cover, intro, totalEps, videos.sortedBy { it.episode })
    }

    fun playback(body: String, requestedServerId: Int): PlaybackInfo? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        
        val qualities = mutableListOf<QualityEntry>()
        val subs = mutableListOf<SubtitleEntry>()

        val qArr = findArrayRecursively(root, "qualityList", "qualities", "sources", "videos", "list", "streams")

        if (qArr != null) {
            for (i in 0 until qArr.length()) {
                val q = qArr.optJSONObject(i) ?: continue
                val url = firstNonEmpty(q, "url", "src", "file", "videoUrl")
                if (url.isNotEmpty()) {
                    val explicitFormat = clean(q, "format")
                    qualities += QualityEntry(label = firstNonEmpty(q, "label", "quality", "name", "resolution").ifEmpty { "Auto" }, url = url, format = explicitFormat)
                }
                collectSubtitlesFromNode(q, subs)
            }
        }

        val direct = findValueRecursively(root, "videoUrl", "url", "streamUrl", "playUrl", "m3u8", "src")
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            qualities.add(0, QualityEntry("Auto", direct, ReelFrenQuality.inferFormat(direct)))
        }
        
        if (qualities.isEmpty()) return null

        collectSubtitlesFromNode(root, subs)

        return PlaybackInfo(
            title = findValueRecursively(root, "title", "name"),
            episodeNumber = findValueRecursively(root, "episodeNumber", "episode").toIntOrNull() ?: 1,
            totalEpisodes = findValueRecursively(root, "totalEpisodes").toIntOrNull() ?: 1,
            locked = findValueRecursively(root, "locked", "isLocked").toBoolean(),
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
        for ((name, code) in langs) { if (lowerUrl.contains(name)) return code }
        return "und"
    }

    private fun firstNonEmpty(o: JSONObject, vararg keys: String): String {
        for (k in keys) { val v = clean(o, k); if (v.isNotEmpty()) return v }
        return ""
    }

    private fun clean(o: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val v = o.optString(key, "").trim()
            if (v.isNotEmpty() && !v.equals("null", ignoreCase = true) && !v.equals("undefined", ignoreCase = true)) return v
        }
        return ""
    }
}

// ----------------------------------------------------------------------
// SUBTITLES
// ----------------------------------------------------------------------
object ReelFrenSubtitles {
    private val PLACEHOLDER_VALUES = setOf("", "null", "none", "false", "0", "#", "-", "n/a", "na", "hardcoded", "undefined")
    private val mediaRegex = Regex("""#EXT-X-MEDIA:([^\r\n]*)""", RegexOption.IGNORE_CASE)
    private val typeRegex  = Regex("""TYPE=SUBTITLES""", RegexOption.IGNORE_CASE)
    private val uriRegex   = Regex("""URI=(?:"([^"]+)"|'([^']+)'|([^,\s]+))""", RegexOption.IGNORE_CASE)
    private val langRegex  = Regex("""LANGUAGE=(?:"([^"]*)"|'([^']*)'|([^,\s]+))""", RegexOption.IGNORE_CASE)
    private val nameRegex  = Regex("""NAME=(?:"([^"]*)"|'([^']*)'|([^,\s]+))""", RegexOption.IGNORE_CASE)

    suspend fun emit(entries: List<SubtitleEntry>, qualities: List<QualityEntry>, callback: (SubtitleFile) -> Unit) {
        val seenUrls = mutableSetOf<String>()
        val seenLabels = mutableMapOf<String, Int>()

        for (entry in entries) {
            val resolved = resolveUrl(entry.url) ?: continue
            if (!seenUrls.add(resolved)) continue

            val baseLabel = resolveDisplayLabel(entry.lang, entry.label)
            val count = seenLabels.getOrDefault(baseLabel, 0)
            seenLabels[baseLabel] = count + 1
            val displayLabel = if (count > 0) "$baseLabel ${count + 1}" else baseLabel
            
            callback(newSubtitleFile(lang = displayLabel, url = resolved) { this.headers = ReelFrenClient.headers(resolved) })
        }

        val hlsUrls = qualities.filter { isHls(it) }.map { ReelFrenClient.absUrl(it.url) }.distinct()
        for (hlsUrl in hlsUrls) {
            for (sub in extractHlsSubs(hlsUrl)) {
                val url = runCatching { sub.url }.getOrNull() ?: continue
                if (seenUrls.add(url)) {
                    val baseLabel = sub.lang
                    val count = seenLabels.getOrDefault(baseLabel, 0)
                    seenLabels[baseLabel] = count + 1
                    val displayLabel = if (count > 0) "$baseLabel ${count + 1}" else baseLabel
                    
                    callback(newSubtitleFile(lang = displayLabel, url = url) { this.headers = ReelFrenClient.headers(url) })
                }
            }
        }
    }

    private fun isHls(q: QualityEntry): Boolean = q.format.equals("hls", ignoreCase = true) || q.url.contains(".m3u8", ignoreCase = true)

    private fun resolveUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.lowercase(Locale.ROOT) in PLACEHOLDER_VALUES) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) return trimmed
        return ReelFrenClient.absUrl(trimmed).ifBlank { null }
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        val clean = relative.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("https://", true) || clean.startsWith("http://", true)) return clean
        if (clean.startsWith("//")) return "https:$clean"
        return runCatching { URI(baseUrl).resolve(clean).toString() }.getOrElse {
            if (clean.startsWith("/")) {
                val origin = runCatching { val u = URI(baseUrl); "${u.scheme}://${u.authority}" }.getOrDefault(ReelFrenStore.apiBase())
                "$origin$clean"
            } else {
                val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/', "")
                if (baseDir.isNotEmpty()) "$baseDir/$clean" else ReelFrenClient.absUrl(clean)
            }
        }
    }

    private fun resolveDisplayLabel(rawLang: String, rawLabel: String): String {
        val nLang = normalizeLang(rawLang)
        var finalLang = nLang
        
        if (nLang == "und") {
            finalLang = normalizeLang(rawLabel)
        }
        
        val fullLangName = langLabel(finalLang)
        
        if (fullLangName != "Unknown") {
            return fullLangName
        }
        
        val cleaned = rawLabel.trim()
        
        if (cleaned.isEmpty()) {
            return "English"
        }
        
        return cleaned.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() 
        }
    }

    private fun normalizeLang(raw: String): String {
        val l = raw.trim().lowercase(Locale.ROOT)
        return when {
            l.isBlank() -> "und"
            l == "en" || l.startsWith("en-") || l.startsWith("en_") || l.contains("english") || l == "eng" -> "en"
            l == "id" || l == "in" || l.startsWith("id-") || l.contains("indonesian") || l.contains("indonesia") || l.contains("bahasa") || l == "ind" -> "id"
            l == "es" || l.startsWith("es-") || l.contains("spanish") || l.contains("español") || l == "spa" -> "es"
            l == "pt" || l.startsWith("pt-") || l.contains("portuguese") || l.contains("português") || l == "por" -> "pt"
            l == "zh" || l.startsWith("zh-") || l.contains("chinese") || l == "chi" || l == "zho" -> "zh"
            l == "ja" || l == "jp" || l.startsWith("ja-") || l.contains("japanese") || l == "jpn" -> "ja"
            l == "ko" || l == "kr" || l.startsWith("ko-") || l.contains("korean") || l == "kor" -> "ko"
            l == "th" || l.startsWith("th-") || l.contains("thai") || l == "tha" -> "th"
            l == "vi" || l.startsWith("vi-") || l.contains("vietnamese") || l == "vie" -> "vi"
            l == "fr" || l.startsWith("fr-") || l.contains("french") || l == "fre" || l == "fra" -> "fr"
            l == "de" || l.startsWith("de-") || l.contains("german") || l == "ger" || l == "deu" -> "de"
            l == "ar" || l.startsWith("ar-") || l.contains("arabic") || l == "ara" -> "ar"
            l == "hi" || l.startsWith("hi-") || l.contains("hindi") || l == "hin" -> "hi"
            l == "fil" || l == "tl" || l.contains("filipino") || l.contains("tagalog") -> "fil"
            l == "ms" || l.startsWith("ms-") || l.contains("malay") || l == "may" || l == "msa" -> "ms"
            l == "ru" || l.startsWith("ru-") || l.contains("russian") || l == "rus" -> "ru"
            l == "tr" || l.startsWith("tr-") || l.contains("turkish") || l == "tur" -> "tr"
            l == "it" || l.startsWith("it-") || l.contains("italian") || l == "ita" -> "it"
            l == "pl" || l.startsWith("pl-") || l.contains("polish") || l == "pol" -> "pl"
            l == "nl" || l.startsWith("nl-") || l.contains("dutch") || l == "dut" || l == "nld" -> "nl"
            l.length == 2 || l.length == 3 -> l
            else -> "und"
        }
    }

    private fun langLabel(lang: String): String = when (lang) {
        "en" -> "English"; "id" -> "Indonesian"; "es" -> "Spanish"; "pt" -> "Portuguese"; "zh" -> "Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"; "th" -> "Thai"; "vi" -> "Vietnamese"; "fr" -> "French"; "de" -> "German"; "ar" -> "Arabic"; "hi" -> "Hindi"; "fil" -> "Filipino"; "ms" -> "Malay"; "ru" -> "Russian"; "tr" -> "Turkish"; "it" -> "Italian"; "pl" -> "Polish"; "nl" -> "Dutch"; "und" -> "Unknown"
        else -> lang.uppercase(Locale.ROOT)
    }

    private fun groupValue(m: MatchResult): String {
        for (i in 1 until m.groupValues.size) { val v = m.groupValues[i]; if (v.isNotEmpty()) return v }
        return ""
    }

    private suspend fun extractHlsSubs(m3u8Url: String): List<SubtitleFile> {
        val manifest = try { app.get(m3u8Url, headers = ReelFrenClient.headers(m3u8Url)).text } catch (ce: CancellationException) { throw ce } catch (e: Exception) { return emptyList() }
        val result = mutableListOf<SubtitleFile>()
        for (m in mediaRegex.findAll(manifest)) {
            val line = m.groupValues.getOrNull(1).orEmpty()
            if (!typeRegex.containsMatchIn(line)) continue
            val uri = uriRegex.find(line)?.let { groupValue(it) } ?: continue
            val abs = resolveRelativeUrl(m3u8Url, uri)
            if (abs.isBlank()) continue
            val rawLang = langRegex.find(line)?.let { groupValue(it) }.orEmpty()
            val rawName = nameRegex.find(line)?.let { groupValue(it) }.orEmpty()
            val displayLabel = resolveDisplayLabel(rawLang, rawName)
            val finalSubUrl = if (abs.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) { resolveSubtitleM3u8ToDirectFile(abs) ?: continue } else { abs }
            result += newSubtitleFile(lang = displayLabel, url = finalSubUrl) { this.headers = ReelFrenClient.headers(finalSubUrl) }
        }
        return result
    }

    private suspend fun resolveSubtitleM3u8ToDirectFile(subM3u8Url: String): String? {
        val subManifest = try { app.get(subM3u8Url, headers = ReelFrenClient.headers(subM3u8Url)).text } catch (ce: CancellationException) { throw ce } catch (e: Exception) { return null }
        val segments = subManifest.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (segments.isEmpty()) return null
        if (segments.size == 1) return resolveRelativeUrl(subM3u8Url, segments[0]).ifBlank { null }
        val sb = StringBuilder()
        for (seg in segments) {
            val segUrl = resolveRelativeUrl(subM3u8Url, seg)
            try { sb.append(app.get(segUrl, headers = ReelFrenClient.headers(segUrl)).text).append("\n") } catch (_: Exception) {}
        }
        if (sb.isNotEmpty()) {
            val base64 = android.util.Base64.encodeToString(sb.toString().toByteArray(), android.util.Base64.NO_WRAP)
            return "data:text/plain;base64,$base64"
        }
        return null
    }
}

// ----------------------------------------------------------------------
// DATA STORE
// ----------------------------------------------------------------------
object ReelFrenStore {
    @Volatile var appContext: Context? = null
        private set

    @Volatile private var storePrefs: SharedPreferences? = null
    @Volatile private var providerPrefs: SharedPreferences? = null
    
    private val memoryCookies = ConcurrentHashMap<String, String>()
    private val rootDomain by lazy { ReelFrenCloudflare.hostOf(ReelFrenConfig.REEL_SITE) }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        storePrefs = appContext?.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
        providerPrefs = appContext?.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)
        ReelFrenLog.isVerbose = providerPrefs?.getBoolean("verbose_logging", false) ?: false
    }

    fun setVerboseLogging(enabled: Boolean) {
        ReelFrenLog.isVerbose = enabled
        providerPrefs?.edit()?.putBoolean("verbose_logging", enabled)?.apply()
    }

    fun isProviderEnabled(slug: String): Boolean {
        val default = ReelFrenConfig.DEFAULT_ENABLED_SLUGS.contains(slug)
        return providerPrefs?.getBoolean("provider_$slug", default) ?: default
    }

    fun setProviderEnabled(editor: SharedPreferences.Editor, slug: String, enabled: Boolean) {
        editor.putBoolean("provider_$slug", enabled)
    }

    fun providerPrefsEditor(): SharedPreferences.Editor? = providerPrefs?.edit()

    fun enabledProviders(): Map<String, String> = ReelFrenConfig.PROVIDERS_MAP.filter { (_, slug) -> isProviderEnabled(slug) }

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
        if (saved.isNotEmpty()) { memoryCookies[norm] = saved; return saved }
        if (norm != rootDomain && norm.endsWith(".$rootDomain")) return cookie(rootDomain)
        return ""
    }

    fun saveCookie(host: String, cookie: String) {
        if (host.isBlank()) return
        val norm = host.lowercase(Locale.ROOT)
        if (cookie.isBlank()) { memoryCookies.remove(norm) } else { memoryCookies[norm] = cookie }
        storePrefs?.edit()?.putString("cookie_$norm", cookie)?.apply()
    }

    fun userAgent(host: String): String {
        return ReelFrenClient.GLOBAL_MOBILE_UA
    }

    fun setUserAgent(host: String, ua: String) {
        // Disabled: We strictly enforce the hardcoded Mobile Chrome UA.
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
        if (success) { editor.putLong("prewarm_last_fail", 0L); editor.putInt("prewarm_fail_count", 0) } 
        else { val count = (storePrefs?.getInt("prewarm_fail_count", 0) ?: 0) + 1; editor.putLong("prewarm_last_fail", System.currentTimeMillis()); editor.putInt("prewarm_fail_count", count) }
        editor.apply()
    }
}

// ----------------------------------------------------------------------
// UTILS
// ----------------------------------------------------------------------
object ReelFrenQuality {
    private val heavy = Regex("(?i)\\b(h\\.?26[56]|hevc|x\\.?26[56]|av1)\\b")
    private val resRegex = Regex("(?i)(\\d{3,4})\\s*p\\b")

    fun label(raw: String) = raw.trim().ifEmpty { "Auto" }

    fun inferFormat(url: String, explicitFormat: String = ""): String {
        val cleanFormat = explicitFormat.trim().lowercase(Locale.ROOT)
        if (cleanFormat.isNotEmpty()) return cleanFormat
        return when {
            url.contains(".m3u8", ignoreCase = true) -> "hls"
            url.contains(".mpd", ignoreCase = true)  -> "dash"
            url.contains(".mp4", ignoreCase = true) || url.contains("mime_type=video_mp4", ignoreCase = true) -> "mp4"
            else -> "video"
        }
    }

    fun linkType(url: String, format: String = ""): ExtractorLinkType {
        val f = inferFormat(url, format)
        return when (f) {
            "hls" -> ExtractorLinkType.M3U8
            "dash" -> ExtractorLinkType.DASH
            "mp4", "video" -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.VIDEO
        }
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

    private fun demote(q: Int) = when { q >= 2160 -> 1080; q >= 1080 -> 720; q >= 720 -> 480; else -> 360 }
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
            val id = m.groupValues[2].trim()
            val ep = epRegex.find(input)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            if (slug.isNotEmpty() && id.isNotEmpty()) return Triple(slug, id, ep)
        }
        val body = input.substringAfter("://", input).substringAfter("/", "").substringBefore("?").substringBefore("#")
        val parts = body.split("|")
        val slug = parts.getOrNull(0)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = parts.getOrNull(1)?.trim().orEmpty()
        val ep = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
        return if (slug.isNotEmpty() && id.isNotEmpty()) Triple(slug, id, ep) else Triple("", "", 1)
    }
}
