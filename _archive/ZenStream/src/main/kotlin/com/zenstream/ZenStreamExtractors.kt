package com.zenstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlin.math.min

// ─── DUMMY BuildConfig ────────────────────────────────────────────────
object BuildConfig {
    const val TMDB_KEY = "dummy_tmdb_key"
    const val SIMKL_CLIENT_ID = "dummy_simkl_client_id"
    const val SIMKL_API = "dummy_simkl_api"
    const val CC_COOKIE = "dummy_cc_cookie"
    const val CASTLE_KEY = "dummy_castle_key"
    const val MOVIEBLAST_TOKEN = "dummy_movieblast_token"
    const val MOVIEBLAST_API = "dummy_movieblast_api"
    const val MOVIEBLAST_KEY = "dummy_movieblast_key"
    const val NETMIRROR_TOKEN = "dummy_netmirror_token"
}

// ─── Constants ──────────────────────────────────────────────────────────
private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
private const val CF_BYPASS_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
private const val CF_LOG_TAG = "ZenStreamCloudflare"
private val imageProxy = "https://wsrv.nl/?url="

// ─── API endpoints ──────────────────────────────────────────────────────
private val malsyncAPI = "https://api.malsync.moe"
private val tokyoInsiderAPI = "https://www.tokyoinsider.com"
private val WYZIESubsAPI = "https://sub.wyzie.io"
private val MostraguardaAPI = "https://mostraguarda.stream"
private val animepaheAPI = "https://animepahe.pw"
private val allmovielandAPI = "https://allmovieland.one"
private val anizoneAPI = "https://anizone.to"
private val PrimeSrcApi = "https://primesrc.me"
private val asiaflixAPI = "https://asiaflix.net"
private val dahmerMoviesAPI = "https://a.111477.xyz"
private val hexaAPI = "https://theemoviedb.hexa.su"
private val videasyAPI = "https://api.speedracelight.com"
private val vidlinkAPI = "https://vidlink.pro"
private val multiDecryptAPI = "https://enc-dec.app/api"
private val animetoshoAPI = "https://feed.animetosho.xyz"
private val animetoshoBaseAPI = "https://animetosho.xyz"
private val anizipAPI = "https://api.ani.zip"
private val vidzeeApi = "https://player.vidzee.wtf"
private val kissKhAPI = "https://kisskh.nl"
private val vidupAPI = "https://vidup.to"
private val bollywoodAPI = "https://tga-hd.api.hashhackers.com"
private val bollywoodBaseAPI = "https://bollywood.eu.org"
private val xpassAPI = "https://play.xpass.top"
private val cinemacityAPI = "https://cinemacity.cc"
private val akwamAPI = "https://akwam.it"
private val levidiaAPI = "https://www.levidia.ch"
private val showboxAPI = "https://showbox.media"
private val febboxAPI = "https://www.febbox.com"
private val anikotoAPI = "https://anikototv.to"
private val projectfreetvAPI = "https://projectfreetv.sx"
private val ctgMoviesBaseAPI = "https://ctgmovies.com"
private val vidrockAPI = "https://vidrock.ru"
private val animekizzAPI = "https://animekizz.live"
private val vidfastProApi = "https://vidfast.vc"
private val onetouchtvAPI = "https://api3.devcorp.me"
private val av1encodesAPI = "https://av1encodes.com"
private val peachifyBaseAPI = "https://peachify.top"
private val reanimeAPI = "https://reanime.to"
private val animesaltAPI = "https://animesalt.ac"
private val anidbAPI = "https://anidb.app"
private val vaPlayerAPI = "https://streamdata.vaplayer.ru"
private val fshareAPI = "https://fsharetv.cc"
private val castleAPI = "https://api.hlowb.com"
private val vidcoreAPI = "https://vidcore.io"
private val anikageAPI = "https://anikage.cc"
private val hdGharTvAPI = "https://hdghartv.cc"
private val aninekoAPI = "https://anineko.to"
internal val torrentioAPI = "https://torrentio.strem.fun/limit=4"
internal val torrentsdbAPI = "https://torrentsdb.com/eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ=="

// ─── Cloudflare helpers ──────────────────────────────────────────────
private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()
private fun mutexFor(url: String): Mutex = cfMutexMap.getOrPut(URI(url).host ?: "default") { Mutex() }
private fun killerFor(url: String): CloudflareKiller = cfKillerMap.getOrPut(URI(url).host ?: "default") { CloudflareKiller() }
private fun isCloudflarePage(response: NiceResponse): Boolean = response.code in listOf(403, 503)
private fun injectWebviewCookies(url: String, headers: Map<String, String>): Map<String, String> = headers

suspend fun cfGet(url: String, headers: Map<String, String> = emptyMap(), allowRedirects: Boolean = true): NiceResponse {
    val headersWithAgent = headers.toMutableMap().apply { if (!containsKey("User-Agent")) this["User-Agent"] = CF_BYPASS_USER_AGENT }
    val effectiveHeaders = injectWebviewCookies(url, headersWithAgent)
    val response = app.get(url, headers = effectiveHeaders, allowRedirects = allowRedirects)
    if (!isCloudflarePage(response)) return response
    return mutexFor(url).withLock {
        val cfKiller = killerFor(url)
        val retryResponse = app.get(url, interceptor = cfKiller, allowRedirects = allowRedirects)
        if (isCloudflarePage(retryResponse)) { cfKiller.savedCookies.clear(); app.get(url, interceptor = cfKiller, allowRedirects = allowRedirects) } else retryResponse
    }
}
suspend fun cfPost(url: String, headers: Map<String, String> = emptyMap(), data: Map<String, String> = emptyMap(), json: Any? = null, allowRedirects: Boolean = true): NiceResponse {
    val headersWithAgent = headers.toMutableMap().apply { if (!containsKey("User-Agent")) this["User-Agent"] = CF_BYPASS_USER_AGENT }
    val effectiveHeaders = injectWebviewCookies(url, headersWithAgent)
    val response = app.post(url, headers = effectiveHeaders, data = data, json = json, allowRedirects = allowRedirects)
    if (!isCloudflarePage(response)) return response
    return mutexFor(url).withLock {
        val cfKiller = killerFor(url)
        val retryResponse = app.post(url, data = data, json = json, interceptor = cfKiller, allowRedirects = allowRedirects)
        if (isCloudflarePage(retryResponse)) { cfKiller.savedCookies.clear(); app.post(url, data = data, json = json, interceptor = cfKiller, allowRedirects = allowRedirects) } else retryResponse
    }
}

object ZenStreamExtractors {

    suspend fun invokeAllSources(res: AllLoadLinksData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tasks = mutableListOf<suspend () -> Unit>()
        tasks.add { invokeShowbox(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeCastle(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeCinemacity(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVidrock(res.tmdbId, res.season, res.episode, callback) }
        tasks.add { invokeAllmovieland(res.imdbId, res.season, res.episode, callback) }
        tasks.add { invokeVideasy(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVidlink(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVaPlayer(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVidup(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVidzee(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokePeachify(res.tmdbId, res.season, res.episode, callback) }
        tasks.add { invokeVidFastPro(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVidcore(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeMoviebox(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeStremioTorrents("Torrentio", torrentioAPI, res.imdbId, res.season, res.episode, callback) }
        tasks.add { invokeStremioTorrents("TorrentsDB", torrentsdbAPI, res.imdbId, res.season, res.episode, callback) }
        tasks.add { invokeStremioSubtitles(res.imdbId, res.season, res.episode, subtitleCallback) }
        tasks.add { invokeWYZIESubs(res.imdbId, res.season, res.episode, subtitleCallback) }
        tasks.add { invokeXpass(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokePrimeSrc(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeHexa(res.tmdbId, res.season, res.episode, callback) }
        tasks.add { invokeHdGharTv(res.title, res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeCtgMovies(res.title, res.season, res.episode, "normal", subtitleCallback, callback) }
        tasks.add { invokeMovieBlast(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeFibwatch(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeFshare(res.title, res.imdbId, subtitleCallback, callback) }
        tasks.add { invokeBollywood(res.title, res.year, res.season, res.episode, callback) }
        tasks.add { invokeVegamovies("VegaMovies", res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeRogmovies(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeBollyflix(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeTopMovies(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeMoviesmod(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeMovies4u(res.imdbId, res.title, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDudefilms(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeUhdmovies(res.title, res.year, res.season, res.episode, callback, subtitleCallback) }
        tasks.add { invokeMoviesdrive(res.title, res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeHindmoviez(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invoke4khdhub(res.title, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeProjectfreetv(res.title, res.airedYear ?: res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeMlsbd(res.title, res.airedYear ?: res.year, res.season, subtitleCallback, callback) }
        tasks.add { invokeLevidia(res.title, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeM4ufree(res.title, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeMultimovies(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAkwam(res.imdbId, res.title, res.airedYear ?: res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeRtally(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAsiaflix(res.title, res.season, res.episode, res.airedYear ?: res.year, subtitleCallback, callback) }
        tasks.add { invokeSkymovies(res.title, res.airedYear ?: res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeHdmovie2(res.title, res.airedYear ?: res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeMostraguarda(res.imdbId, subtitleCallback, callback) }
        tasks.add { invokeOnetouchtv(res.title, res.airedYear ?: res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeKisskh(res.title, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeToonstream(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimekizz(res.title, res.anilistId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimesalt(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeZinkmovies(res.title, res.year, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDahmerMovies(res.title, res.year, res.season, res.episode, callback) }
        tasks.add { invokeAnizone(res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeTokyoInsider(res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimetosho(res.kitsuId, res.malId, res.episode, callback) }
        tasks.add { invokeAnimetoshoHttp(res.title, res.malId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimepahe(res.imdbId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnikage(res.title, res.anilistId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnineko(res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimedao(res.imdbTitle, res.title, res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnikoto(res.imdbTitle ?: res.title, res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnidb(res.imdbTitle ?: res.title, res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeReanime(res.anilistId, res.episode, subtitleCallback, callback) }
        // Donghua
        tasks.add { invokeDonghuaGeneric("Animekhor", "https://animekhor.org", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Donghuastream", "https://donghuastream.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Donghuafun", "https://donghuafun.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Animexin", "https://animexin.vip", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Donghuaworld", "https://donghuaworld.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("LuciferDonghua", "https://luciferdonghua.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("MyAnimeLive", "https://myanimelive.com", res.title, res.episode, subtitleCallback, callback) }
        runLimitedAsync(concurrency = 10, *tasks.toTypedArray())
    }

    suspend fun invokeAllAnimeSources(res: AllLoadLinksData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tasks = mutableListOf<suspend () -> Unit>()
        tasks.add { invokeAnimepahe(res.imdbId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnikage(res.title, res.anilistId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimetosho(res.kitsuId, res.malId, res.episode, callback) }
        tasks.add { invokeAnimetoshoHttp(res.title, res.malId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeTokyoInsider(res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnizone(res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimekizz(res.title, res.anilistId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimesalt(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnikoto(res.imdbTitle ?: res.title, res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnidb(res.imdbTitle ?: res.title, res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeReanime(res.anilistId, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnimedao(res.imdbTitle, res.title, res.year, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAnineko(res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeAv1encodes(res.imdbTitle, res.imdbSeason, res.imdbEpisode, callback) }
        tasks.add { invokeToonstream(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeStremioTorrents("Torrentio", torrentioAPI, "kitsu:${res.kitsuId}", res.season, res.episode, callback) }
        tasks.add { invokeStremioTorrents("TorrentsDB", torrentsdbAPI, "kitsu:${res.kitsuId}", res.season, res.episode, callback) }
        tasks.add { invokeStremioSubtitles(res.imdbId, res.season, res.episode, subtitleCallback) }
        tasks.add { invokeWYZIESubs(res.imdbId, res.season, res.episode, subtitleCallback) }
        tasks.add { invokeMoviebox(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeCastle(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeCinemacity(res.title, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVaPlayer(res.imdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeVidzee(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        tasks.add { invokeXpass(res.tmdbId, res.season, res.episode, subtitleCallback, callback) }
        // Donghua
        tasks.add { invokeDonghuaGeneric("Animekhor", "https://animekhor.org", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Donghuastream", "https://donghuastream.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Donghuafun", "https://donghuafun.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Animexin", "https://animexin.vip", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("Donghuaworld", "https://donghuaworld.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("LuciferDonghua", "https://luciferdonghua.com", res.title, res.episode, subtitleCallback, callback) }
        tasks.add { invokeDonghuaGeneric("MyAnimeLive", "https://myanimelive.com", res.title, res.episode, subtitleCallback, callback) }
        runLimitedAsync(concurrency = 10, *tasks.toTypedArray())
    }

    // ─── All provider functions (copied from original) ──────────────
    suspend fun invokeShowbox(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (imdbId == null) return
        val token = "dummy_token"
        val mediaId = searchSuperstream(imdbId) ?: return
        val type = if (season != null) 2 else 1
        val shareKey = getShareKey(mediaId, type) ?: return
        val rootData = getFileList(shareKey) ?: return
        val fileList = rootData.file_list ?: return
        val qualities: List<VideoQuality> = if (season != null && episode != null) {
            val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
            val seasonFolder = fileList.firstOrNull { f ->
                f.is_dir && f.file_name?.lowercase()?.let {
                    it.contains("season $season") || it.contains("s$seasonSlug")
                } == true
            } ?: fileList.firstOrNull { it.is_dir } ?: return
            val epData = getFileList(shareKey, seasonFolder.fid) ?: return
            val epList = epData.file_list ?: return
            val epFile = epList.firstOrNull { f ->
                if (f.is_dir) false
                else f.file_name?.lowercase()?.let {
                    it.contains("e$episodeSlug") || it.contains("ep$episodeSlug") || it.contains("episode $episode")
                } == true
            } ?: epList.firstOrNull { !it.is_dir } ?: return
            getVideoQualities(epFile.fid, shareKey, token)
        } else {
            val videoFile = fileList.firstOrNull { !it.is_dir } ?: return
            getVideoQualities(videoFile.fid, shareKey, token)
        }
        val VIDEO_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.8",
            "Connection" to "keep-alive",
            "Range" to "bytes=0-",
            "Referer" to "https://www.febbox.com",
            "User-Agent" to USER_AGENT
        )
        qualities.forEach { q ->
            val isOrg = if (q.quality == "ORG") "ORG" else ""
            callback.invoke(
                newExtractorLink(
                    "Showbox",
                    "ShowBox $isOrg",
                    q.url,
                    if (q.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = if (isOrg == "ORG") Qualities.P2160.value else getIndexQuality(q.quality)
                    this.headers = VIDEO_HEADERS
                }
            )
        }
    }

    suspend fun invokeCastle(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title.isNullOrBlank()) return
        val pkg = "com.external.castle"
        val channel = "IndiaA"
        val clientType = "1"
        val apiLang = "en-US"
        try {
            val securityKey = getCastleSecurityKey("$castleAPI/v0.1/system/getSecurityKey/1?channel=$channel&clientType=$clientType&lang=$apiLang")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "$castleAPI/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=$clientType&keyword=$encodedTitle&lang=$apiLang&mode=1&packageName=$pkg&page=1&size=30"
            val searchData = makeCastleApiRequest(searchUrl, securityKey)
            val rows = searchData.optJSONArray("rows") ?: return
            var movieId = ""
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val rowTitle = row.optString("title").ifEmpty { row.optString("name") }
                if (rowTitle.contains(title, ignoreCase = true) || title.contains(rowTitle, ignoreCase = true)) {
                    movieId = row.optString("id").ifEmpty { row.optString("redirectId").ifEmpty { row.optString("redirectIdStr") } }
                    if (movieId.isNotEmpty()) break
                }
            }
            if (movieId.isEmpty() && rows.length() > 0) {
                val first = rows.getJSONObject(0)
                movieId = first.optString("id").ifEmpty { first.optString("redirectId").ifEmpty { first.optString("redirectIdStr") } }
            }
            if (movieId.isEmpty()) return
            var details = makeCastleApiRequest("$castleAPI/film-api/v1.9.9/movie?channel=$channel&clientType=$clientType&lang=$apiLang&movieId=$movieId&packageName=$pkg", securityKey)
            var effectiveMovieId = movieId
            if (season != null && episode != null) {
                val seasons = details.optJSONArray("seasons")
                if (seasons != null) {
                    for (i in 0 until seasons.length()) {
                        val s = seasons.getJSONObject(i)
                        if (s.optInt("number") == season) {
                            val seasonMovieId = s.optString("movieId")
                            if (seasonMovieId.isNotEmpty() && seasonMovieId != movieId) {
                                details = makeCastleApiRequest("$castleAPI/film-api/v1.9.9/movie?channel=$channel&clientType=$clientType&lang=$apiLang&movieId=$seasonMovieId&packageName=$pkg", securityKey)
                                effectiveMovieId = seasonMovieId
                            }
                            break
                        }
                    }
                }
            }
            val episodes = details.optJSONArray("episodes") ?: return
            var episodeId = ""
            var targetEpisode: JSONObject? = null
            if (season != null && episode != null) {
                for (i in 0 until episodes.length()) {
                    val ep = episodes.getJSONObject(i)
                    if (ep.optInt("number") == episode) {
                        episodeId = ep.optString("id")
                        targetEpisode = ep
                        break
                    }
                }
            } else if (episodes.length() > 0) {
                targetEpisode = episodes.getJSONObject(0)
                episodeId = targetEpisode.optString("id")
            }
            if (episodeId.isEmpty() || targetEpisode == null) return
            val videoUrl = "$castleAPI/film-api/v2.0.1/movie/getVideo2?clientType=$clientType&packageName=$pkg&channel=$channel&lang=$apiLang"
            val body = mapOf(
                "mode" to "1",
                "appMarket" to "GuanWang",
                "clientType" to clientType,
                "woolUser" to "false",
                "apkSignKey" to CASTLE_KEY,
                "androidVersion" to "13",
                "movieId" to effectiveMovieId,
                "episodeId" to episodeId,
                "isNewUser" to "true",
                "resolution" to "2",
                "packageName" to pkg
            )
            val videoData = makeCastleApiRequest(videoUrl, securityKey, method = "POST", jsonBody = body)
            val defaultVideoUrl = videoData.optString("videoUrl", "")
            if (defaultVideoUrl.isEmpty()) return
            callback.invoke(
                newExtractorLink(
                    "Castle",
                    "Castle Auto (USE VLC)",
                    defaultVideoUrl,
                    ExtractorLinkType.M3U8
                ) { this.referer = castleAPI }
            )
            val subtitles = videoData.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.getJSONObject(i)
                    val subUrl = sub.optString("url")
                    if (subUrl.isNotBlank()) {
                        val lang = sub.optString("abbreviate").ifEmpty { sub.optString("title", "English") }
                        mySubtitleCallback(lang, subUrl, subtitleCallback, "Castle")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Castle", "CRASHED: ${e.message}")
        }
    }

    suspend fun invokeCinemacity(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf("Cookie" to CC_COOKIE)
        val movieUrl = cfGet(
            "$cinemacityAPI/search/$title/",
            headers = headers
        ).document
            .selectFirst("a.e-nowrap")
            ?.attr("href")
            ?: return
        val scriptData = cfGet(movieUrl, headers).document
            .select("script:containsData(atob)")
            .getOrNull(1)
            ?.data()
            ?: return
        val playerJson = JSONObject(
            base64Decode(
                scriptData.substringAfter("atob(\"").substringBefore("\")")
            ).substringAfter("new Playerjs(").substringBeforeLast(");")
        )
        val fileArray = JSONArray(playerJson.getString("file"))
        fun extractQuality(url: String): Int {
            return when {
                url.contains("2160p") -> Qualities.P2160.value
                url.contains("1440p") -> Qualities.P1440.value
                url.contains("1080p") -> Qualities.P1080.value
                url.contains("720p") -> Qualities.P720.value
                url.contains("480p") -> Qualities.P480.value
                url.contains("360p") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }
        suspend fun emitSubtitles(subtitleStr: String?) {
            if (subtitleStr.isNullOrEmpty()) return
            val regex = Regex("""\[(.*?)\](https?://[^,]+)""")
            regex.findAll(subtitleStr).forEach { match ->
                val lang = match.groupValues[1]
                val url = match.groupValues[2]
                mySubtitleCallback(lang, url, subtitleCallback, "CineCity")
            }
        }
        suspend fun emitExtractorLinks(files: String) {
            callback.invoke(
                newExtractorLink(
                    "CineCity",
                    "CineCity Multi Audio 🌐",
                    files,
                    INFER_TYPE
                ) {
                    referer = movieUrl
                    quality = extractQuality(files)
                }
            )
        }
        val first = fileArray.getJSONObject(0)
        if (!first.has("folder")) {
            emitExtractorLinks(first.getString("file"))
            return
        }
        for (i in 0 until fileArray.length()) {
            val seasonJson = fileArray.getJSONObject(i)
            val seasonNumber = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(seasonJson.optString("title"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (season != null && seasonNumber != season) continue
            val episodes = seasonJson.getJSONArray("folder")
            for (j in 0 until episodes.length()) {
                val epJson = episodes.getJSONObject(j)
                val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epJson.optString("title"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (episode != null && episodeNumber != episode) continue
                emitSubtitles(epJson.optString("subtitle"))
                emitExtractorLinks(epJson.getString("file"))
            }
        }
    }

    suspend fun invokeVidrock(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        val query = if (type == "movie") "$tmdbId" else "${tmdbId}_${season}_${episode}"
        val apiUrl = "$vidrockAPI/api/$type/$query/"
        val headers = mapOf(
            "Origin" to vidrockAPI,
            "Referer" to "$vidrockAPI/",
            "User-Agent" to USER_AGENT
        )
        val responseText = app.get(apiUrl, headers = headers).text
        val jsonObject = JSONObject(responseText)
        jsonObject.keys().forEach { serverName ->
            val serverData = jsonObject.optJSONObject(serverName) ?: return@forEach
            val encryptedUrl = serverData.optString("url", "")
            if (encryptedUrl.isNotEmpty() && encryptedUrl != "error" && encryptedUrl != "null") {
                val decryptedUrl = decryptVidrockUrl(encryptedUrl) ?: return@forEach
                val linkType = if (decryptedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                callback.invoke(
                    newExtractorLink(
                        "Vidrock[$serverName]",
                        "Vidrock[$serverName]",
                        decryptedUrl,
                        linkType
                    ) { this.headers = headers }
                )
            }
        }
    }

    suspend fun invokeAllmovieland(id: String?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val playerScript = app.get("https://allmovieland.link/player.js?v=60%20128").toString()
        val domainRegex = Regex("const AwsIndStreamDomain.*'(.*)';")
        val host = domainRegex.find(playerScript)?.groupValues?.getOrNull(1) ?: return
        val referer = "$allmovielandAPI/"
        val res =
            app.get("$host/play/$id", referer = referer)
                .document
                .selectFirst("script:containsData(playlist)")
                ?.data()
                ?.substringAfter("{")
                ?.substringBefore(";")
                ?.substringBefore(")")
        val json = tryParseJson<AllMovielandPlaylist>("{${res ?: return}}")
        val headers = mapOf("X-CSRF-TOKEN" to "${json?.key}")
        val serverRes =
            app.get(fixUrl(json?.file ?: return, host), headers = headers, referer = referer)
                .text
                .replace(Regex(""",\s*\[]"""), "")
        val servers =
            tryParseJson<ArrayList<AllMovielandServer>>(serverRes).let { server ->
                if (season == null) {
                    server?.map { it.file to it.title }
                } else {
                    server
                        ?.find { it.id.equals("$season") }
                        ?.folder
                        ?.find { it.episode.equals("$episode") }
                        ?.folder
                        ?.map { it.file to it.title }
                }
            }
        servers?.safeAmap { (server, lang) ->
            val path =
                app.post(
                    "${host}/playlist/${server ?: return@safeAmap}.txt",
                    headers = headers,
                    referer = referer
                ).text
            callback.invoke(
                newExtractorLink(
                    "Allmovieland [$lang]",
                    "Allmovieland [$lang]",
                    path,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    suspend fun invokeVideasy(title: String?, tmdbId: Int?, imdbId: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Origin" to "https://player.videasy.to",
            "Referer" to "https://player.videasy.to/"
        )
        val servers = listOf(
            "myflixerzupcloud",
            "downloader2",
            "m4uhd",
            "hdmovie",
            "cdn",
            "superflix",
            "lamovie",
            "jett",
            "tejo",
            "neon2",
            "ym"
        )
        if (title == null) return
        val firstPass = quote(title)
        val encTitle = quote(firstPass)
        val enc = 2
        val seedJson = app.get("$videasyAPI/seed?mediaId=$tmdbId", headers = headers).text
        val json = JSONObject(seedJson)
        val seed = json.getString("seed")
        servers.safeAmap { server ->
            val url = if (season == null) {
                "$videasyAPI/$server/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=$enc&seed=$seed"
            } else {
                "$videasyAPI/$server/sources-with-title?title=$encTitle&mediaType=tv&year=$year&tmdbId=$tmdbId&episodeId=$episode&seasonId=$season&imdbId=$imdbId&enc=$enc&seed=$seed"
            }
            val enc_data = app.get(url, headers = headers).text
            val jsonBody = mapOf("text" to enc_data, "id" to tmdbId, "seed" to seed)
            val response = app.post(
                "$multiDecryptAPI/dec-videasy",
                json = jsonBody
            )
            if (response.isSuccessful) {
                val json = response.text
                val result = JSONObject(json).getJSONObject("result")
                val sourcesArray = result.getJSONArray("sources")
                for (i in 0 until sourcesArray.length()) {
                    val obj = sourcesArray.getJSONObject(i)
                    val quality = obj.getString("quality")
                    val source = obj.getString("url")
                    val type = if (source.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else if (source.contains(".mp4") || source.contains(".mkv")) {
                        ExtractorLinkType.VIDEO
                    } else {
                        INFER_TYPE
                    }
                    callback.invoke(
                        newExtractorLink(
                            "Videasy[${server.capitalizeServer()}]",
                            "Videasy[${server.capitalizeServer()}] $quality",
                            source,
                            type
                        ) {
                            this.quality = getIndexQuality(quality)
                            this.headers = headers
                        }
                    )
                }
                val subtitlesArray = result.getJSONArray("subtitles")
                for (i in 0 until subtitlesArray.length()) {
                    val obj = subtitlesArray.getJSONObject(i)
                    val source = obj.getString("url")
                    val language = obj.getString("language")
                    mySubtitleCallback(language, source, subtitleCallback, "Videasy")
                }
            }
        }
    }

    suspend fun invokeVidlink(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = "$multiDecryptAPI/enc-vidlink?text=$tmdbId"
        val json = app.get(url).text
        val enc_data = JSONObject(json).getString("result")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Mi 9T Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Mobile Safari/537.36 EdgA/95.0.1020.48",
            "Connection" to "keep-alive",
            "Referer" to "$vidlinkAPI/",
            "Origin" to vidlinkAPI,
        )
        val epUrl = if (season == null) {
            "$vidlinkAPI/api/b/movie/$enc_data"
        } else {
            "$vidlinkAPI/api/b/tv/$enc_data/$season/$episode"
        }
        val epJson = app.get(epUrl, headers = headers).text
        val streamRes = tryParseJson<VidLinkStreamResponse>(epJson)
        val qualitiesMap = streamRes?.stream?.qualities
        if (qualitiesMap.isNullOrEmpty()) return
        qualitiesMap.forEach { (qualityKey, qualityData) ->
            val videoUrl = qualityData.url
            if (!videoUrl.isNullOrEmpty()) {
                val mappedQuality = when (qualityKey) {
                    "1080" -> Qualities.P1080.value
                    "720" -> Qualities.P720.value
                    "480" -> Qualities.P480.value
                    "360" -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                val streamHeaders = qualityData.headers ?: mapOf(
                    "Referer" to "https://filmboom.top/",
                    "Origin" to "https://filmboom.top"
                )
                val isM3u8 = qualityData.type == "m3u8" || videoUrl.contains(".m3u8", true)
                callback(
                    newExtractorLink(
                        source = "VidLink",
                        name = "VidLink",
                        url = videoUrl,
                        if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = streamHeaders["referer"] ?: streamHeaders["Referer"] ?: "https://filmboom.top/"
                        this.headers = streamHeaders
                        this.quality = mappedQuality
                    }
                )
            }
        }
        val captions = streamRes.stream.captions
        captions?.forEach { caption ->
            if (!caption.url.isNullOrEmpty() && !caption.language.isNullOrEmpty()) {
                mySubtitleCallback(caption.language, caption.url, subtitleCallback, "VidLink")
            }
        }
    }

    suspend fun invokeVaPlayer(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val referer = "https://nextgencloudfabric.com/"
        val url = if (season == null) {
            "$vaPlayerAPI/api.php?imdb=$imdbId&type=movie"
        } else {
            "$vaPlayerAPI/api.php?imdb=$imdbId&type=tv&season=$season&episode=$episode"
        }
        val json = app.get(url, referer = referer).text
        val res = tryParseJson<VaPlayerResponse>(json) ?: return
        res.data?.stream_urls?.safeAmap { streamUrl ->
            M3u8Helper.generateM3u8(
                "VaPlayer",
                streamUrl,
                referer
            ).forEach(callback)
        }
        res.default_subs?.amap { sub ->
            if (!sub.url.isNullOrBlank()) {
                mySubtitleCallback(sub.lang ?: sub.code, sub.url, subtitleCallback, "VaPlayer")
            }
        }
    }

    suspend fun invokeVidup(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$vidupAPI/",
            "X-Requested-With" to "XMLHttpRequest"
        )
        val url = if (season != null) {
            "$vidupAPI/tv/$tmdbId/$season/$episode"
        } else {
            "$vidupAPI/movie/$tmdbId"
        }
        val text = app.get(url).text
        val regex = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""")
        val enc = regex.find(text)?.groupValues?.get(1) ?: return
        val responseText = app.get("$multiDecryptAPI/enc-vidup?text=$enc", headers = headers).text
        val parsedData = tryParseJson<VidupResponse>(responseText)
        if (parsedData?.status != 200) return
        val result = parsedData.result ?: return
        val serversUrl = result.servers ?: return
        val streamUrl = result.stream ?: return
        val token = result.token ?: return
        val postHeaders = headers + mapOf("X-CSRF-Token" to token)
        val serversEncrypted = app.post(serversUrl, headers = postHeaders).text
        val decResponseText = app.post(
            "$multiDecryptAPI/dec-vidup",
            json = mapOf("text" to serversEncrypted)
        ).text
        val parsedServers = tryParseJson<VidupServersResponse>(decResponseText)
        if (parsedServers?.status != 200) return
        val serverList = parsedServers.result ?: return
        serverList.safeAmap { server ->
            val serverData = server.data ?: return@safeAmap
            val serverName = server.name ?: "Vidup"
            val currentStreamUrl = "$streamUrl/$serverData"
            val streamEncrypted = app.post(currentStreamUrl, headers = postHeaders).text
            val finalDecText = app.post(
                "$multiDecryptAPI/dec-vidup",
                json = mapOf("text" to streamEncrypted)
            ).text
            val finalStreamData = tryParseJson<VidupStreamResponse>(finalDecText)
            val streamResult = finalStreamData?.result
            if (finalStreamData?.status == 200 && streamResult != null) {
                val finalUrl = streamResult.url
                if (finalUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            "Vidup",
                            "Vidup $serverName",
                            finalUrl,
                            if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = "$vidupAPI/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
                streamResult.tracks?.forEach { track ->
                    val subUrl = track.file
                    val subLabel = track.label ?: "Unknown"
                    if (subUrl != null) {
                        mySubtitleCallback(subLabel, subUrl, subtitleCallback, "Vidup")
                    }
                }
            }
        }
    }

    suspend fun invokeVidzee(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val secret = base64Decode("QTdrUDl4TTJRdjhMcjROejFIdDZZYzNCdzVKZjBEc1U=")
        val defaultReferer = "$vidzeeApi/"
        val servers = listOf(0, 1, 2, 4, 5, 6, 7)
        servers.safeAmap { sr ->
            try {
                val apiUrl = if (season == null) {
                    "$vidzeeApi/api/server?id=$tmdbId&sr=$sr"
                } else {
                    "$vidzeeApi/api/server?id=$tmdbId&sr=$sr&ss=$season&ep=$episode"
                }
                val response = app.get(apiUrl).text
                val json = JSONObject(response)
                val globalHeaders = mutableMapOf<String, String>()
                json.optJSONObject("headers")?.let { headersObj ->
                    headersObj.keys().forEach { key ->
                        globalHeaders[key] = headersObj.getString(key)
                    }
                }
                val urls = json.optJSONArray("url") ?: JSONArray()
                for (i in 0 until urls.length()) {
                    val obj = urls.getJSONObject(i)
                    val encryptedLink = obj.optString("link")
                    val name = obj.optString("name", "")
                    val type = obj.optString("type", "hls")
                    val lang = obj.optString("lang", "Unknown")
                    val flag = obj.optString("flag", "")
                    if (encryptedLink.isNotBlank()) {
                        val finalUrl = decryptVidzeeUrl(encryptedLink, secret) ?: continue
                        if (!finalUrl.contains("https:")) continue
                        val headersMap = mutableMapOf<String, String>()
                        headersMap.putAll(globalHeaders)
                        val referer = headersMap["referer"] ?: defaultReferer
                        val displayName =
                            if (flag.isNotBlank()) "VidZee $name ($lang - $flag)" else " VidZee$name ($lang)"
                        callback.invoke(
                            newExtractorLink(
                                "VidZee",
                                displayName,
                                finalUrl,
                                if (type.equals("hls", ignoreCase = true))
                                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer
                                this.headers = headersMap
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
                val subs = json.optJSONArray("tracks") ?: JSONArray()
                for (i in 0 until subs.length()) {
                    val sub = subs.getJSONObject(i)
                    val subLang = sub.optString("lang", "Unknown")
                    val subUrl = sub.optString("url")
                    if (subUrl.isNotBlank()) mySubtitleCallback(subLang, subUrl, subtitleCallback, "Vidzee")
                }
            } catch (e: Exception) {
                Log.e("Vidzee", "Failed sr=$sr: $e")
            }
        }
    }

    suspend fun invokePeachify(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Origin" to "$peachifyBaseAPI",
            "Referer" to "$peachifyBaseAPI/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:139.0) Gecko/20100101 Firefox/139.0"
        )
        val servers = listOf(
            "https://usa.eat-peach.sbs/holly",
            "https://usa.eat-peach.sbs/multi",
            "https://usa.eat-peach.sbs/air",
            "https://uwu.eat-peach.sbs/net",
            "https://uwu.eat-peach.sbs/moviebox"
        )
        servers.safeAmap { server ->
            val url = if (season == null) "$server/movie/$tmdbId" else "$server/tv/$tmdbId/$season/$episode"
            val text = app.get(url, headers = headers).text
            val encrypt = JSONObject(text).optString("data").ifEmpty { return@safeAmap }
            val decrypted = peachifyDecrypt(encrypt) ?: return@safeAmap
            val json = JSONObject(decrypted)
            val provider = json.optString("providerName", "Peachify")
            val sources = json.optJSONArray("sources") ?: return@safeAmap
            for (i in 0 until sources.length()) {
                val src = sources.getJSONObject(i)
                val rawUrl = src.optString("url").ifEmpty { continue }
                val dub = src.optString("dub", "")
                val srcType = src.optString("type", "hls")
                val quality = src.optInt("quality", 0)
                val srcHeaders = src.optJSONObject("headers")
                val isProxy = rawUrl.contains("/m3u8-proxy") || rawUrl.contains("/mp4-proxy")
                val (finalUrl, proxyHeaders) = if (isProxy) {
                    val query = URI(rawUrl).query?.queryParams() ?: emptyMap()
                    val realUrl = query["url"] ?: rawUrl
                    val headersObj = query["headers"]
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    realUrl to headersObj.toStringMap()
                } else {
                    rawUrl to srcHeaders.toStringMap()
                }
                val finalReferer = proxyHeaders["referer"] ?: srcHeaders?.optString("referer") ?: "$peachifyBaseAPI/"
                val finalOrigin = proxyHeaders["origin"] ?: srcHeaders?.optString("origin") ?: peachifyBaseAPI
                val finalUA = proxyHeaders["user-agent"] ?: srcHeaders?.optString("user-agent") ?: USER_AGENT
                val name = buildString {
                    append("Peachify[${provider.capitalizeServer()}]")
                    if (dub.isNotEmpty()) append(" • $dub")
                }
                val type = if (srcType == "hls") ExtractorLinkType.M3U8 else INFER_TYPE
                callback.invoke(
                    newExtractorLink("Peachify", name, finalUrl, type) {
                        this.headers = mapOf(
                            "Origin" to finalOrigin,
                            "Referer" to finalReferer,
                            "User-Agent" to finalUA
                        )
                        this.quality = quality
                    }
                )
            }
        }
    }

    suspend fun invokeVidFastPro(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) "$vidfastProApi/movie/$tmdbId/" else "$vidfastProApi/tv/$tmdbId/$season/$episode/"
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$vidfastProApi/",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val response = app.get(url, headers = headers).text
        val encodedText = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""").find(response)?.groupValues?.get(1) ?: return
        val decApiUrl = "$multiDecryptAPI/enc-vidfast?text=$encodedText"
        val decodedDataJson = app.get(decApiUrl).text
        val decodedData = tryParseJson<EncDecResponse>(decodedDataJson)?.result ?: return
        val serversUrl = decodedData.servers ?: return
        val streamBaseUrl = decodedData.stream ?: return
        val token = decodedData.token ?: return
        headers["X-CSRF-Token"] = token
        val serversEncrypted = app.post(serversUrl, headers = headers).text
        val serversListJson = app.post(
            "$multiDecryptAPI/dec-vidfast",
            json = mapOf("text" to serversEncrypted)
        ).text
        val serversList = tryParseJson<VidfastStreamResponse>(serversListJson)?.result ?: return
        serversList.safeAmap { server ->
            val serverHash = server.data ?: return@safeAmap
            val finalStreamUrl = "$streamBaseUrl/$serverHash"
            val streamDataEncrypted = app.post(finalStreamUrl, headers = headers).text
            if (streamDataEncrypted.isNullOrBlank()) return@safeAmap
            val streamDataJson = app.post(
                "$multiDecryptAPI/dec-vidfast",
                json = mapOf("text" to streamDataEncrypted)
            ).text
            val streamData = tryParseJson<VidfastServersStreamRoot>(streamDataJson)?.result ?: return@safeAmap
            streamData.tracks?.forEach { track ->
                if (track.file != null && track.label != null) {
                    mySubtitleCallback(track.label, track.file, subtitleCallback, "Vidfast")
                }
            }
            val fileUrl = streamData.url ?: return@safeAmap
            val type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val is4k = streamData.is4kAvailable == true || server.description?.contains("4K", true) == true
            val quality = if (is4k) Qualities.P2160.value else Qualities.P1080.value
            callback.invoke(
                newExtractorLink(
                    "Vidfast[${server.name}]",
                    "Vidfast[${server.name}] ${server.description ?: ""}",
                    fileUrl,
                    type
                ) {
                    this.headers = headers
                    this.quality = quality
                }
            )
        }
    }

    suspend fun invokeVidcore(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$vidcoreAPI/",
            "X-Requested-With" to "XMLHttpRequest"
        )
        val baseUrl = if (season == null) {
            "$vidcoreAPI/movie/$tmdbId"
        } else {
            "$vidcoreAPI/tv/$tmdbId/$season/$episode"
        }
        val pageContent = app.get(baseUrl).text
        val regex = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""")
        val match = regex.find(pageContent) ?: return
        val encryptedText = match.groupValues[1]
        val encVidcoreUrl = "$multiDecryptAPI/enc-vidcore?text=${URLEncoder.encode(encryptedText, "UTF-8")}"
        val initialResponse = app.get(encVidcoreUrl).parsedSafe<VidcoreResponse>()?.result ?: return
        val serversUrl = initialResponse.servers
        val streamUrl = initialResponse.stream
        val token = initialResponse.token
        headers["X-CSRF-Token"] = token
        val serversEncrypted = app.post(serversUrl, headers = headers).text
        val decServersResponse = app.post(
            "$multiDecryptAPI/dec-vidcore",
            json = mapOf("text" to serversEncrypted),
            headers = headers
        ).parsedSafe<VidcoreServers>()?.result ?: return
        decServersResponse.safeAmap { server ->
            val stream = "$streamUrl/${server.data}"
            val streamEncrypted = app.post(stream, headers = headers).text
            val decryptedStream = app.post(
                "$multiDecryptAPI/dec-vidcore",
                json = mapOf("text" to streamEncrypted)
            ).parsedSafe<VidcoreStreamResponse>()?.result ?: return@safeAmap
            val m3u8Url = decryptedStream.url
            M3u8Helper.generateM3u8(
                "Vidcore - ${server.name}",
                m3u8Url,
                "$vidcoreAPI/",
            ).forEach(callback)
            decryptedStream.tracks?.forEach { track ->
                mySubtitleCallback(track.label, track.file, subtitleCallback, "Vidcore")
            }
        }
    }

    suspend fun invokeMoviebox(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        fun unwrapData(json: JSONObject): JSONObject {
            val data = json.optJSONObject("data") ?: return json
            return data.optJSONObject("data") ?: data
        }
        val HOST = "h5-api.aoneroom.com"
        val BASE_URL = "https://$HOST"
        val SEASON_SUFFIX_REGEX = """\sS\d+(?:-S?\d+)*$""".toRegex(RegexOption.IGNORE_CASE)
        val xUser = app.get(
            "$BASE_URL/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox"
        ).headers.get("x-user")
        if (xUser.isNullOrEmpty()) return
        val token = JSONObject(xUser).optString("token", "")
        if (token.isNullOrEmpty()) return
        val baseHeaders = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to BASE_URL,
            "Host" to HOST,
            "Connection" to "keep-alive",
            "Authorization" to "Bearer $token",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        )
        val subjectType = if (season != null) 2 else 1
        val searchObj = try {
            JSONObject(
                app.post(
                    "$BASE_URL/wefeed-h5api-bff/subject/search",
                    headers = baseHeaders,
                    json = mapOf(
                        "keyword" to title,
                        "page" to 1,
                        "perPage" to 24,
                        "subjectType" to subjectType
                    )
                ).text
            )
        } catch (e: Exception) { return }
        val items = unwrapData(searchObj).optJSONArray("items") ?: return
        val titleMatchRegex = """^${Regex.escape(title ?: "")}(?:\s+\[([^\]]+)\])?$""".toRegex(RegexOption.IGNORE_CASE)
        val uniqueIdsWithLang = mutableMapOf<String, String>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId")
            if (id.isEmpty()) continue
            val cleanTitle = item.optString("title", "").replace(SEASON_SUFFIX_REGEX, "")
            val matchResult = titleMatchRegex.find(cleanTitle) ?: continue
            val language = matchResult.groups[1]?.value ?: "Original"
            uniqueIdsWithLang.putIfAbsent(id, language)
        }
        if (uniqueIdsWithLang.isEmpty()) return
        uniqueIdsWithLang.forEach { (subjectId, language) ->
            val detailObj = try {
                JSONObject(
                    app.get(
                        "https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId"
                    ).text
                )
            } catch (e: Exception) { return@forEach }
            val detailPath = detailObj
                .optJSONObject("data")
                ?.optJSONArray("items")
                ?.optJSONObject(0)
                ?.optJSONObject("subject")
                ?.optString("detailPath", "") ?: return@forEach
            val params = buildString {
                append("subjectId=$subjectId")
                if (season != null) append("&se=$season&ep=$episode")
                append("&detailPath=$detailPath")
            }
            val reqHeaders = baseHeaders + mapOf(
                "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                "Origin" to "https://fmoviesunblocked.net"
            )
            val downloadObj = try {
                JSONObject(app.get("$BASE_URL/wefeed-h5api-bff/subject/download?$params", headers = reqHeaders).text)
            } catch (e: Exception) { JSONObject() }
            val playObj = try {
                JSONObject(app.get("$BASE_URL/wefeed-h5api-bff/subject/play?$params", headers = reqHeaders).text)
            } catch (e: Exception) { JSONObject() }
            val downloadData = unwrapData(downloadObj)
            val playData = unwrapData(playObj)
            val addedQualities = mutableSetOf<Int>()
            val downloads = downloadData.optJSONArray("downloads")
            if (downloads != null) {
                for (i in 0 until downloads.length()) {
                    val d = downloads.optJSONObject(i) ?: continue
                    val dlink = d.optString("url")
                    val isVip = d.optBoolean("vipLocked", false)
                    val resolution = d.optInt("resolution")
                    if (dlink.isNotEmpty() && !isVip) {
                        addedQualities.add(resolution)
                        callback.invoke(
                            newExtractorLink(
                                "MovieBox [$language]",
                                "MovieBox [$language]",
                                dlink,
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "https://fmoviesunblocked.net/",
                                    "Origin" to "https://fmoviesunblocked.net"
                                )
                                this.quality = resolution
                            }
                        )
                    }
                }
            }
            val streams = playData.optJSONArray("streams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val slink = s.optString("url")
                    val isVip = s.optBoolean("vipLocked", false)
                    val resString = s.optString("resolutions", "")
                    val resolution = resString.toIntOrNull() ?: s.optInt("resolution", 0)
                    if (slink.isNotEmpty() && !isVip && !addedQualities.contains(resolution)) {
                        addedQualities.add(resolution)
                        callback.invoke(
                            newExtractorLink(
                                "MovieBox [$language]",
                                "MovieBox [$language]",
                                slink,
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "https://fmoviesunblocked.net/",
                                    "Origin" to "https://fmoviesunblocked.net"
                                )
                                this.quality = resolution
                            }
                        )
                    }
                }
            }
            val dashStreams = playData.optJSONArray("dash")
            if (dashStreams != null) {
                for (i in 0 until dashStreams.length()) {
                    val d = dashStreams.optJSONObject(i) ?: continue
                    val dlink = d.optString("url")
                    val isVip = d.optBoolean("vipLocked", false)
                    if (dlink.isNotEmpty() && !isVip) {
                        callback.invoke(
                            newExtractorLink(
                                "MovieBox Auto [$language]",
                                "MovieBox Auto [$language] (DASH)",
                                dlink,
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "https://fmoviesunblocked.net/",
                                    "Origin" to "https://fmoviesunblocked.net"
                                )
                            }
                        )
                    }
                }
            }
            val subtitles = downloadData.optJSONArray("captions")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.optJSONObject(i) ?: continue
                    val slink = s.optString("url")
                    if (slink.isNotEmpty()) {
                        val lanName = s.optString("lanName").takeIf { it.isNotEmpty() } ?: s.optString("lan")
                        mySubtitleCallback(lanName, slink, subtitleCallback, "Moviebox")
                    }
                }
            }
        }
    }

    suspend fun invokeStremioTorrents(sourceName: String, api: String, id: String?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) {
            "$api/stream/movie/$id.json"
        } else if (id?.contains("kitsu") == true) {
            "$api/stream/series/$id:$episode.json"
        } else {
            "$api/stream/series/$id:$season:$episode.json"
        }
        val res = app.get(url, timeout = 200L).parsedSafe<TorrentioResponse>()
        res?.streams?.forEach { stream ->
            val title = stream.title ?: stream.description ?: stream.name ?: ""
            val seedersRegex = """[👤👥]\s*(\d+)""".toRegex()
            val seeders = seedersRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val sizeRegex = """💾\s*([0-9.]+\s*[A-Za-z]+)""".toRegex()
            val fileSize = sizeRegex.find(title)?.groupValues?.get(1) ?: ""
            if (seeders < 25) return@forEach
            val magnet = buildMagnetString(stream)
            callback.invoke(
                newExtractorLink(
                    "$sourceName🧲",
                    sourceName.toSansSerifBold() + " 🧲 | 👤 $seeders ⬆️ | " + getSimplifiedTitle(title + fileSize),
                    magnet,
                    ExtractorLinkType.MAGNET,
                ) {
                    this.quality = getIndexQuality(stream.name)
                }
            )
        }
    }

    suspend fun invokeStremioSubtitles(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        val subsUrls = listOf(
            "https://opensubtitles.stremio.homes/en|hi|de|ar|tr|es|ta|te|ru|ko/ai-translated=true|from=all|auto-adjustment=true",
            """https://subsense.nepiraw.com/n0tcjfba-{"languages":["en","hi","ta","es","ar"],"maxSubtitles":10}"""
        )
        subsUrls.safeAmap { subUrl ->
            try {
                val url = if (season != null) {
                    subUrl + "/subtitles/series/$imdbId:$season:$episode.json"
                } else {
                    subUrl + "/subtitles/movie/$imdbId.json"
                }
                val json = app.get(url).text
                val subtitleResponse = parseJson<StremioSubtitleResponse>(json)
                subtitleResponse.subtitles.forEach {
                    val lang = it.lang ?: it.lang_code
                    val fileUrl = it.url
                    if (lang != null && fileUrl != null) {
                        mySubtitleCallback(lang, fileUrl, subtitleCallback, "StremioSubtitle")
                    }
                }
            } catch (e: Exception) {
                println("Error fetching/parsing subtitle from: $subUrl - ${e.message}")
            }
        }
    }

    suspend fun invokeWYZIESubs(id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = if (season != null) "$WYZIESubsAPI/search?id=$id&season=$season&episode=$episode&source=all&key=dummy_key" else "$WYZIESubsAPI/search?id=$id&source=all&key=dummy_key"
        val json = app.get(url, timeout = 10000).text
        val data = tryParseJson<ArrayList<WYZIESubtitle>>(json)
        data?.forEach {
            val lang = it.display ?: it.language
            mySubtitleCallback(lang ?: return@forEach, it.url, subtitleCallback, "WyzieSubs")
        }
    }

    suspend fun invokeXpass(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val embedUrl = if (season == null) "$xpassAPI/e/movie/$tmdbId" else "$xpassAPI/e/tv/$tmdbId/$season/$episode"
        val html = app.get(embedUrl, referer = "$xpassAPI/").text
        val backups = extractXpassBackups(html)
        backups.safeAmap { (name, url) ->
            val fullUrl = if (url.startsWith("http")) url else xpassAPI + url
            val json = app.get(fullUrl).text
            val sources = JSONObject(json)
                .optJSONArray("playlist")
                ?.optJSONObject(0)
                ?.optJSONArray("sources") ?: return@safeAmap
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val file = source.optString("file").takeIf {
                    it.isNotBlank() && it.startsWith("http")
                } ?: continue
                val isM3u8 = source.optString("type").contains("hls", ignoreCase = true)
                        || file.contains(".m3u8")
                if (isM3u8) {
                    M3u8Helper.generateM3u8(
                        "Xpass [$name]",
                        file,
                        "$xpassAPI/",
                    ).forEach(callback)
                } else {
                    callback.invoke(
                        newExtractorLink(
                            "Xpass [$name]",
                            "Xpass [$name]",
                            file
                        ) {
                            this.referer = "$xpassAPI/"
                        }
                    )
                }
            }
        }
    }

    suspend fun invokePrimeSrc(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Referer" to "$PrimeSrcApi/",
            "User-Agent" to USER_AGENT
        )
        val url = if (season == null) {
            "$PrimeSrcApi/api/v1/s?imdb=$imdbId&type=movie"
        } else {
            "$PrimeSrcApi/api/v1/s?imdb=$imdbId&season=$season&episode=$episode&type=tv"
        }
        val serverJson = app.get(url, timeout = 30, headers = headers).text
        val serverList = tryParseJson<PrimeSrcServerList>(serverJson) ?: return
        serverList.servers?.safeAmap {
            val rawServerJson = cfGet("$PrimeSrcApi/api/v1/l?key=${it.key}", headers).text
            val jsonObject = JSONObject(rawServerJson)
            loadSourceNameExtractor("PrimeWire", jsonObject.optString("link", ""), PrimeSrcApi, subtitleCallback, callback)
        }
    }

    suspend fun invokeHexa(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) {
            "$hexaAPI/api/tmdb/movie/$tmdbId/images"
        } else {
            "$hexaAPI/api/tmdb/tv/$tmdbId/season/$season/episode/$episode/images"
        }
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val key = keyBytes.joinToString("") { "%02x".format(it) }
        val tokenResponseText = app.get("$multiDecryptAPI/enc-hexa").text
        val token = JSONObject(tokenResponseText).getJSONObject("result").getString("token")
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/plain",
            "X-Api-Key" to key,
            "X-Fingerprint-Lite" to "e9136c41504646444",
            "Referer" to "https://hexa.su/",
            "X-Cap-Token" to token
        )
        val enc_data = app.get(url, headers = headers).text
        val jsonBody = mapOf("text" to enc_data, "key" to key)
        val response = app.post(
            "$multiDecryptAPI/dec-hexa",
            json = jsonBody,
            headers = mapOf("Content-Type" to "application/json")
        )
        if (response.isSuccessful) {
            val json = response.text
            val result = JSONObject(json).getJSONObject("result")
            val sourcesArray = result.getJSONArray("sources")
            for (i in 0 until sourcesArray.length()) {
                val src = sourcesArray.getJSONObject(i)
                val server = src.getString("server")
                val m3u8 = src.getString("url")
                M3u8Helper.generateM3u8(
                    "Hexa ${server.capitalizeServer()}",
                    m3u8,
                    "https://hexa.su/",
                ).forEach(callback)
            }
        }
    }

    suspend fun invokeHdGharTv(title: String?, tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (season == null) "movies" else "series"
        val searchJson = app.get("$hdGharTvAPI/api/search?q=$title&type=all&page=1").text
        val searchResponse = tryParseJson<HdGharSearchResponse>(searchJson) ?: return
        val allItems = searchResponse.movies.orEmpty() + searchResponse.series.orEmpty()
        val matchedId = allItems.find { it.tmdbId == tmdbId }?.id ?: return
        val detailsJson = app.get("$hdGharTvAPI/api/$type/public/$matchedId").text
        val detailsResponse = tryParseJson<HdGharDetailsResponse>(detailsJson) ?: return
        val extractedLinks = if (type == "movies") {
            detailsResponse.streamingLinks.orEmpty()
        } else {
            val targetSeason = detailsResponse.seasons?.find { it.seasonNumber == season }
            val targetEpisode = targetSeason?.episodes?.find { it.episodeNumber == episode }
            targetEpisode?.streamingLinks.orEmpty()
        }
        extractedLinks.forEach { link ->
            val url = link.url ?: return@forEach
            val quality = getIndexQuality(link.quality)
            val isM3u8 = link.type?.contains("hls", ignoreCase = true) == true || url.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    "HdGharTv",
                    "HdGharTv",
                    url,
                    if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = quality
                    this.referer = "$hdGharTvAPI/"
                }
            )
        }
    }

    suspend fun invokeCtgMovies(title: String?, season: Int?, episode: Int?, type: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val contentType = if (type == "anime") {
            "anime"
        } else if (season != null) {
            "tv"
        } else {
            "movies"
        }
        val slug = title.createSlug() ?: return
        val html = app.get("$ctgMoviesBaseAPI/$contentType/$slug").text
        val allLinks = parseCtgLinks(html)
        if (allLinks.isEmpty()) return
        val links = if (season != null && episode != null) {
            allLinks.filter { it.seasonNumber == season && it.episodeNumber == episode }
                .ifEmpty { allLinks }
        } else {
            allLinks
        }
        if (links.isEmpty()) return
        val STREAM_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "identity",
            "Referer" to "$ctgMoviesBaseAPI/",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site",
            "DNT" to "1"
        )
        links.forEach { link ->
            val playUrl = link.hlsUrl ?: link.url
            val isM3u8 = playUrl.contains(".m3u8") || link.hlsUrl != null
            callback.invoke(
                newExtractorLink(
                    "CTGMovies",
                    "CTGMovies ${link.source}",
                    playUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = getIndexQuality(link.quality)
                    this.headers = STREAM_HEADERS
                }
            )
        }
    }

    suspend fun invokeMovieBlast(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to "MovieBlast",
            "Referer" to "MovieBlast",
            "x-request-x" to "com.movieblast"
        )
        val encodedTitle = URLEncoder.encode(title ?: "", "UTF-8").replace("+", "%20")
        val searchResponseText = app.get("$MOVIEBLAST_API/search/$encodedTitle/$MOVIEBLAST_TOKEN").text
        val searchData = tryParseJson<MovieBlastSearchResponse>(searchResponseText) ?: return
        val expectedType = if (season == null) "movie" else "serie"
        val validResults = searchData.search?.filter { it.type == expectedType } ?: return
        val targetItem = validResults.find { item ->
            item.name.equals(title, ignoreCase = true) ||
            item.originalName.equals(title, ignoreCase = true)
        }
        val id = targetItem?.id ?: return
        val contentType = if (season == null) "media/detail" else "series/show"
        val detailsResponseText = app.get("$MOVIEBLAST_API/$contentType/$id/$MOVIEBLAST_TOKEN").text
        val detailsData = tryParseJson<MovieBlastDetailsResponse>(detailsResponseText) ?: return
        val videos = if (season == null) {
            detailsData.videos
        } else {
            detailsData.seasons
                ?.find { it.seasonNumber == season }
                ?.episodes
                ?.find { it.episodeNumber == episode }
                ?.videos
        }
        videos?.forEach { video ->
            val rawLink = video.link ?: return@forEach
            val fullUrl = if (rawLink.startsWith("http")) rawLink else "https://$rawLink"
            val signedUrl = generateSignedUrl(fullUrl) ?: return@forEach
            val server = video.server ?: ""
            val lang = video.lang ?: ""
            callback.invoke(
                newExtractorLink(
                    "MovieBlast",
                    "MovieBlast(Multi Audio)",
                    signedUrl,
                    if (signedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = getIndexQuality(server)
                    this.headers = headers
                }
            )
        }
        detailsData.subtitles?.forEach { sub ->
            var subLink = sub.link
            if (!subLink.isNullOrEmpty()) {
                subLink = if (subLink.startsWith("http")) subLink else "https://$subLink"
                mySubtitleCallback(sub.lang, subLink, subtitleCallback, "MovieBlast")
            }
        }
    }

    suspend fun invokeFibwatch(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title.isNullOrBlank()) return
        val isTv = season != null && episode != null
        val fibwatchSeEpisodeRegex = Regex("""s(\d{1,2})e(\d{1,3})""")
        val fibwatchDirectMediaRegex = Regex("""\.(mp4|mkv|m3u8)""", RegexOption.IGNORE_CASE)
        val searchUrl = """$fibwatchBaseUrl/search?keyword=${URLEncoder.encode(title, "UTF-8")}&page_id=1"""
        val searchDoc = app.get(searchUrl, headers = fibwatchHeaders).document
        val searchResults = searchDoc.select("div.video-thumb").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val resultTitle = el.selectFirst("p.hptag")?.text()?.trim()
                ?: el.selectFirst("div.video-thumb img")?.attr("alt")
                ?: ""
            resultTitle to href
        }
        if (searchResults.isEmpty()) return
        val titleLower = title.lowercase()
        val match = searchResults.firstOrNull { it.first.lowercase().contains(titleLower) }
            ?: searchResults.first()
        val detailUrl = if (match.second.startsWith("http")) match.second else fibwatchBaseUrl + match.second
        val detailDoc = app.get(detailUrl, headers = fibwatchHeaders).document
        val videoId = detailDoc.selectFirst("input#video-id")?.attr("value") ?: return
        val candidates = mutableListOf<FibwatchSource>()
        if (isTv) {
            val episodesUrl = "$fibwatchBaseUrl/ajax/episodes.php?video_id=$videoId"
            val episodesResp = app.get(episodesUrl, headers = fibwatchHeaders)
                .parsedSafe<FibwatchEpisodesResponse>()
            val episodes = episodesResp?.episodes.orEmpty()
            if (episodes.isEmpty()) return
            var episodePageUrl = episodes.firstNotNullOfOrNull { ep ->
                val epTitleLower = ep.title?.lowercase() ?: return@firstNotNullOfOrNull null
                val m = fibwatchSeEpisodeRegex.find(epTitleLower) ?: return@firstNotNullOfOrNull null
                val epSeason = m.groupValues[1].toIntOrNull()
                val epNum = m.groupValues[2].toIntOrNull()
                if (epSeason == season && epNum == episode) ep.url else null
            }
            if (episodePageUrl.isNullOrBlank()) {
                episodePageUrl = episodes.firstOrNull()?.url
            }
            if (episodePageUrl.isNullOrBlank()) return
            val fullEpisodeUrl =
                if (episodePageUrl.startsWith("http")) episodePageUrl else fibwatchBaseUrl + episodePageUrl
            val episodeDoc = app.get(fullEpisodeUrl, headers = fibwatchHeaders).document
            val episodeVideoId = episodeDoc.selectFirst("input#video-id")?.attr("value") ?: return
            val switcherUrl = "$fibwatchBaseUrl/ajax/resolution_switcher.php?video_id=$episodeVideoId"
            val switcherResp = app.get(switcherUrl, headers = fibwatchHeaders).parsedSafe<FibwatchSwitcherResponse>()
            candidates.addAll(switcherResp?.current.orEmpty())
            candidates.addAll(switcherResp?.popup.orEmpty())
        } else {
            val switcherUrl = "$fibwatchBaseUrl/ajax/resolution_switcher.php?video_id=$videoId"
            val switcherResp = app.get(switcherUrl, headers = fibwatchHeaders).parsedSafe<FibwatchSwitcherResponse>()
            candidates.addAll(switcherResp?.current.orEmpty())
            candidates.addAll(switcherResp?.popup.orEmpty())
        }
        val seenUrls = mutableSetOf<String>()
        candidates.safeAmap { candidate ->
            var candUrl = candidate.url?.trim().takeUnless { it.isNullOrBlank() } ?: return@safeAmap
            if (!candUrl.startsWith("http")) candUrl = fibwatchBaseUrl + candUrl
            val fallbackQuality = extractFibwatchQuality(candidate.res ?: candUrl)
            val resolvedUrl: String
            val quality: String
            if (fibwatchDirectMediaRegex.containsMatchIn(candUrl)) {
                resolvedUrl = candUrl
                quality = fallbackQuality
            } else {
                val result = resolveFibwatchStream(candUrl, fallbackQuality) ?: return@safeAmap
                resolvedUrl = result.first
                quality = result.second
            }
            if (!seenUrls.add(resolvedUrl)) return@safeAmap
            val type = if (isTv) "(Combined)" else ""
            if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(
                    "FibWatch $type",
                    resolvedUrl,
                    referer = fibwatchPlaybackHeaders["Referer"] ?: "",
                    headers = fibwatchPlaybackHeaders
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        "FibWatch $type",
                        "FibWatch $type",
                        resolvedUrl,
                    ) {
                        this.quality = getIndexQuality(quality)
                        this.headers = fibwatchPlaybackHeaders
                    }
                )
            }
        }
    }

    suspend fun invokeFshare(title: String?, imdbId: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        fun String?.qualityInt(): Int = this?.toIntOrNull() ?: 0
        val slug = "$title episode 1 $imdbId".createSlug()
        val url = "$fshareAPI/w/$slug"
        val doc = app.get(url).document
        val regex = Regex("""Movie\.setSource\('([^']+)'""")
        val match = regex.find(doc.toString())
        val token = match?.groupValues?.get(1) ?: return
        val trailer = doc.selectFirst("input#trailer")?.attr("value") ?: return
        val json = app.get("$fshareAPI/api/file/$token/source?trailer=$trailer&type=watch").text
        val parsed = tryParseJson<FshareResponse>(json) ?: return
        val allSources = parsed.data.file.sources + parsed.data.file.alternatives.flatten()
        val headers = mapOf(
            "referer" to url,
            "user-agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        )
        allSources.distinctBy { it.id }.forEach { source ->
            callback(
                newExtractorLink(
                    "Fshare",
                    "Fshare",
                    fshareAPI + source.src,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = source.quality.qualityInt()
                    this.headers = headers
                }
            )
        }
    }

    suspend fun invokeBollywood(title: String?, year: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val titleSlug = title?.replace(" ", ".")
        val headers = mapOf(
            "Origin" to bollywoodBaseAPI,
            "Referer" to "$bollywoodBaseAPI/",
            "User-Agent" to USER_AGENT,
            "Authorization" to "Bearer ${"dummy_token"}"
        )
        val url = if (season == null) {
            "$bollywoodAPI/mix_media_files/search?q=${titleSlug}.${year}&page=1"
        } else {
            "$bollywoodAPI/mix_media_files/search?q=${titleSlug}.S${seasonSlug}E${episodeSlug}&page=1"
        }
        val response = app.get(
            url,
            headers = headers,
            timeout = 300000
        ).text
        val jsonObject = JSONObject(response)
        if (!jsonObject.has("files")) return
        val filesArray = jsonObject.getJSONArray("files")
        (0 until filesArray.length())
            .map { filesArray.getJSONObject(it) }
            .filter { item -> !item.optString("file_name").contains(".$titleSlug") }
            .take(5)
            .map { item ->
                item to app.get(
                    "$bollywoodAPI/genLink?type=mix_media&id=${item.optString("id")}",
                    headers = headers
                ).text
            }
            .forEach { (item, res) ->
                val fileName = item.optString("file_name")
                val fileId = item.optString("id")
                val size = formatSize(item.optString("file_size").toLong())
                val linkJson = JSONObject(res)
                if (!linkJson.has("url")) return@forEach
                val streamUrl = linkJson.optString("url")
                val simplifiedTitle = getSimplifiedTitle("$fileName $size")
                callback.invoke(
                    newExtractorLink(
                        "GramCinema",
                        "[GramCinema]".toSansSerifBold() + " ${simplifiedTitle}",
                        streamUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getIndexQuality(fileName)
                        this.referer = bollywoodBaseAPI
                    }
                )
            }
    }

    suspend fun invokeVegamovies(sourceName: String, id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (id == null) return
        val api = if (sourceName == "VegaMovies") vegamoviesAPI else rogmoviesAPI
        val searchUrl = "$api/search.php?q=$id&page=1"
        val json = app.get(searchUrl).text
        val movieUrls = tryParseJson<VegaSearchResponse>(json)?.hits?.map { hit ->
            val permalink = hit.document.permalink
            fixUrl(permalink, api)
        } ?: emptyList()
        movieUrls.safeAmap { pageUrl ->
            val res = app.get(pageUrl).document
            val currentId = res.select("a[href*=\"imdb\"]").attr("href").substringAfter("title/").substringBefore("/")
            if (currentId != id) return@safeAmap
            if (season == null) {
                res.select("button.dwd-button").safeAmap {
                    val link = it.parent()?.attr("href") ?: return@safeAmap
                    val doc = app.get(link).document
                    doc.select("p > a").safeAmap { source ->
                        loadSourceNameExtractor(sourceName, source.attr("href"), referer = "", subtitleCallback, callback)
                    }
                }
            } else {
                res.select("h4:matches((?i)(Season $season)), h3:matches((?i)(Season $season))").safeAmap { h4 ->
                    h4.nextElementSibling()?.select("a:matches((?i)(V-Cloud|Single|Episode|G-Direct))")?.safeAmap {
                        val doc = app.get(it.attr("href")).document
                        val epLink = doc.selectFirst("h4:contains(Episode):contains($episode)")
                            ?.nextElementSibling()
                            ?.selectFirst("a:matches((?i)(V-Cloud))")
                            ?.attr("href")
                            ?: return@safeAmap
                        loadSourceNameExtractor(sourceName, epLink, referer = "", subtitleCallback, callback)
                    }
                }
            }
        }
    }

    suspend fun invokeRogmovies(id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        invokeVegamovies("RogMovies", id, season, episode, subtitleCallback, callback)
    }

    suspend fun invokeBollyflix(id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res1 = app.get("$bollyflixAPI/search/$id").document
        res1.select("div > article > a").safeAmap {
            val url = it.attr("href")
            val res = app.get(url).document
            val hTag = if (season == null) "h5" else "h4"
            val sTag = if (season == null) "" else "Season $season"
            val entries =
                res.select("div.thecontent.clearfix > $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
                    .filter { element -> !element.text().contains("Download", true) }
            entries.safeAmap {
                var href = it.nextElementSibling()?.select("a")?.attr("href") ?: return@safeAmap
                if (!href.contains("fastdlserver") && href.contains("?id=")) {
                    val token = href.substringAfter("id=")
                    val encodedurl =
                        app.get("https://web.sidexfee.com/?id=$token").text.substringAfter("link\":\"")
                            .substringBefore("\"};")
                    href = base64Decode(encodedurl)
                }
                if (season == null) {
                    loadSourceNameExtractor("Bollyflix", href, "", subtitleCallback, callback)
                } else {
                    val episodeText = "Episode " + episode.toString().padStart(2, '0')
                    val link =
                        app.get(href).document.selectFirst("article h3 a:contains($episodeText)")!!
                            .attr("href")
                    loadSourceNameExtractor("Bollyflix", link, "", subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invokeTopMovies(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val hTag = if (season == null) "h3" else "div.single_post h3"
        val aTag = if (season == null) "Download" else "G-Drive"
        val sTag = if (season == null) "" else "(Season $season)"
        app.get("$topmoviesAPI/search/$imdbId").document.select("#content_box article > a").safeAmap { element ->
            val res = app.get(
                element.attr("href"),
                headers = mapOf("User-Agent" to USER_AGENT)
            ).document
            val entries = if (season == null) {
                res.select("$hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p|4K))")
                    .filter { element -> !element.text().contains("Batch/Zip", true) && !element.text().contains("Info:", true) }.reversed()
            } else {
                res.select("$hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p|4K))")
                    .filter { element -> !element.text().contains("Batch/Zip", true) || !element.text().contains("720p & 480p", true) || !element.text().contains("Series Info", true)}
            }
            entries.safeAmap {
                val source =
                    it.nextElementSibling()?.select("a.maxbutton:contains($aTag)")?.attr("href")
                val selector =
                    if (season == null) "a.maxbutton-5:contains(Server)" else "h3 a:matches(Episode $episode)"
                if (!source.isNullOrEmpty()) {
                    app.get(
                        source,
                        headers = mapOf("User-Agent" to USER_AGENT)
                    ).document.selectFirst(selector)
                        ?.attr("href")?.let {
                            val link = bypassHrefli(it).toString()
                            loadSourceNameExtractor("Topmovies", link, referer = "", subtitleCallback, callback)
                        }
                }
            }
        }
    }

    suspend fun invokeMoviesmod(id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        invokeModflix(id, season, episode, subtitleCallback, callback, moviesmodAPI)
    }

    suspend fun invokeModflix(id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, api: String) {
        var url = ""
        if (season == null) {
            url = "$api/search/$id"
        } else {
            url = "$api/search/$id $season"
        }
        var href = app.get(url).document.selectFirst("#content_box article > a")?.attr("href")
        val hTag = if (season == null) "h4" else "h3"
        val aTag = if (season == null) "Download" else "Episode"
        val sTag = if (season == null) "" else "(S0$season|Season $season)"
        val res = app.get(
            href ?: return,
        ).document
        val entries = res.select("div.thecontent $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
            .filter { element ->
                val text = element.text()
                !text.contains("MoviesMod", true)
            }
        entries.safeAmap { it ->
            var link =
                it.nextElementSibling()?.select("a:contains($aTag)")?.attr("href")
                    ?.substringAfter("=") ?: ""
            val selector =
                if (season == null) "p a.maxbutton" else "h3 a:matches(Episode $episode)"
            if (link.isNotEmpty()) {
                val source = app.get(link).document.selectFirst(selector)?.attr("href") ?: return@safeAmap
                val bypassedLink = bypassHrefli(source).toString()
                loadSourceNameExtractor("Moviesmod", bypassedLink, "", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeMovies4u(id: String?, title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val searchQuery = if (season == null) "${title?.replace(" ", "+")}+${year}" else "${title?.replace(" ", "+")}+season+${season}"
        val searchUrl = "$movies4uAPI/?s=$searchQuery"
        val headers = mapOf(
            "Cookie" to "xla=s4t",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Referer" to "$movies4uAPI/"
        )
        val searchDoc = app.get(searchUrl, headers = headers).document
        val links = searchDoc.select("article h3 a")
        links.safeAmap { element ->
            val postUrl = element.attr("href")
            val postDoc = app.get(postUrl, headers = headers).document
            val imdbId = postDoc.select("p a:contains(IMDb Rating)").attr("href")
                            .substringAfter("title/").substringBefore("/")
            if (imdbId != id.toString()) { return@safeAmap }
            if (season == null) {
                val innerUrl = postDoc.select("div.download-links-div a.btn").attr("href")
                val innerDoc = app.get(innerUrl, headers = headers).document
                val sourceButtons = innerDoc.select("div.downloads-btns-div a.btn")
                sourceButtons.safeAmap { sourceButton ->
                    val sourceLink = sourceButton.attr("href")
                    loadSourceNameExtractor(
                        "Movies4u",
                        sourceLink,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            } else {
                val seasonBlocks = postDoc.select("div.downloads-btns-div")
                seasonBlocks.safeAmap { block ->
                    val headerText = block.previousElementSibling()?.text().orEmpty()
                    if (headerText.contains("Season $season", ignoreCase = true)) {
                        val seasonLink = block.selectFirst("a.btn")?.attr("href") ?: return@safeAmap
                        val episodeDoc = app.get(seasonLink, headers = headers).document
                        val episodeBlocks = episodeDoc.select("div.downloads-btns-div")
                        if (episode != null && episode in 1..episodeBlocks.size) {
                            val episodeBlock = episodeBlocks[episode - 1]
                            val episodeLinks = episodeBlock.select("a.btn")
                            episodeLinks.safeAmap { epLink ->
                                val sourceLink = epLink.attr("href")
                                loadSourceNameExtractor(
                                    "Movies4u",
                                    sourceLink,
                                    "",
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun invokeDudefilms(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (imdbId == null) return
        val urls = app.get("$dudefilmsAPI/?s=$imdbId").document.select("a.simple-grid-grid-post-thumbnail-link")
        urls.safeAmap {
            val url = it.attr("href")
            val doc = app.get(url).document
            if (season == null && episode == null) {
                doc.select("a.maxbutton").safeAmap { link ->
                    val href = link.attr("href")
                    val document = app.get(href).document
                    document.select("a.maxbutton").safeAmap { source ->
                        loadSourceNameExtractor("Dudefilms", source.attr("href"), "", subtitleCallback, callback)
                    }
                }
            } else {
                val matchingH4Tags = doc.select("h4").filter {
                    Regex("""Season\s*0*$season\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
                }
                if (matchingH4Tags.isEmpty()) return@safeAmap
                matchingH4Tags.safeAmap { h4Tag ->
                    var currentSibling = h4Tag.nextElementSibling()
                    while (currentSibling != null) {
                        val tagName = currentSibling.tagName()
                        if (tagName != "p") return@safeAmap
                        if (tagName == "p") {
                            currentSibling.select("a").safeAmap{ aTag ->
                                val source = aTag.attr("href")
                                val epSource = app.get(source).document
                                    .select("a.maxbutton")
                                    .find { Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() == episode }
                                    ?.attr("href") ?: return@safeAmap
                                loadSourceNameExtractor("Dudefilms", epSource, "", subtitleCallback, callback)
                            }
                        }
                        currentSibling = currentSibling.nextElementSibling()
                    }
                }
            }
        }
    }

    suspend fun invokeUhdmovies(title: String?, year: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = app.get("$uhdmoviesAPI/search/$title $year").document
            .select("article div.entry-image a").attr("href")
        val doc = app.get(url).document
        val selector = if (season == null) {
            "div.entry-content p:matches($year)"
        } else {
            "div.entry-content p:matches((?i)(S0?$season|Season 0?$season))"
        }
        val epSelector = if (season == null) {
            "a:matches((?i)(Download))"
        } else {
            "a:matches((?i)(Episode $episode))"
        }
        val links = doc.select(selector).mapNotNull {
            val nextElementSibling = it.nextElementSibling()
            nextElementSibling?.select(epSelector)?.attr("href")
        }
        links.safeAmap {
            if (!it.isNullOrEmpty()) {
                val driveLink = if (it.contains("driveleech") || it.contains("driveseed")) {
                    val baseUrl = getBaseUrl(it)
                    val text = app.get(it).text
                    val regex = Regex("""window\.location\.replace\(["'](.*?)["']\)""")
                    val fileId = regex.find(text)?.groupValues?.get(1) ?: return@safeAmap
                    baseUrl + fileId
                } else {
                    bypassHrefli(it) ?: return@safeAmap
                }
                loadSourceNameExtractor(
                    "UHDMovies",
                    driveLink,
                    "",
                    subtitleCallback,
                    callback,
                )
            }
        }
    }

    suspend fun invokeMoviesdrive(title: String?, imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = "$moviesdriveAPI/search.php?q=$imdbId"
        val jsonString = app.get(url).text
        val root = JSONObject(jsonString)
        if (!root.has("hits")) return
        val hits = root.getJSONArray("hits")
        for (i in 0 until hits.length()) {
            val hit = hits.getJSONObject(i)
            val doc = hit.getJSONObject("document")
            val currentImdbId = doc.optString("imdb_id")
            if (imdbId == currentImdbId) {
                val matchedItem = moviesdriveAPI + doc.optString("permalink")
                val document = app.get(matchedItem).document
                if (season == null) {
                    document.select("h5 > a").safeAmap {
                        val href = it.attr("href")
                        val server = extractMdrive(href)
                        server.safeAmap {
                            loadSourceNameExtractor("MoviesDrive", it, "", subtitleCallback, callback)
                        }
                    }
                } else {
                    val (sSlug, eSlug) = getEpisodeSlug(season, episode)
                    val stag = "Season $season|S$sSlug"
                    val sep = "Ep$eSlug|Ep$episode"
                    val entries = document.select("h5:matches((?i)$stag)")
                    entries.safeAmap { entry ->
                        val href = entry.nextElementSibling()?.selectFirst("a")?.attr("href") ?: ""
                        if (href.isNotBlank()) {
                            val doc = app.get(href).document
                            val fEp = doc.selectFirst("h5:matches((?i)$sep)")
                            val linklist = mutableListOf<String>()
                            val source1 = fEp?.nextElementSibling()?.selectFirst("a")?.attr("href")
                            val source2 = fEp?.nextElementSibling()?.nextElementSibling()?.selectFirst("a")?.attr("href")
                            if (source1 != null) linklist.add(source1)
                            if (source2 != null) linklist.add(source2)
                            linklist.safeAmap { url ->
                                loadSourceNameExtractor(
                                    "MoviesDrive",
                                    url,
                                    "",
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun invokeHindmoviez(id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        app.get("$hindMoviezAPI/?s=$id", timeout = 5000L).document.select("h2.entry-title > a").safeAmap {
            val doc = app.get(it.attr("href"), timeout = 5000L).document
            if (episode == null) {
                doc.select("a.maxbutton").safeAmap {
                    val res = app.get(it.attr("href"), timeout = 5000L).document
                    val link = res.selectFirst("a.get-link-btn")
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { href ->
                            val baseurl = href.substringBefore("/?id=")
                            val rawId = href.substringAfter("id=")
                            hindmoviezsignHShare(rawId, baseurl)
                        }
                        ?: return@safeAmap
                    getHindMoviezLinks("HindMoviez", link, subtitleCallback, callback)
                }
            } else {
                doc.select("a.maxbutton").safeAmap {
                    val text = it.parent()?.parent()?.previousElementSibling()?.text() ?: ""
                    if (text.contains("Season $season")) {
                        val res = app.get(it.attr("href"), timeout = 5000L).document
                        val link = res.select("h3 > a")
                            .getOrNull(episode - 1)
                            ?.attr("href")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { href ->
                                val baseurl = href.substringBefore("/?id=")
                                val rawId = href.substringAfter("id=")
                                hindmoviezsignHShare(rawId, baseurl)
                            } ?: return@safeAmap
                        getHindMoviezLinks("HindMoviez", link, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    suspend fun invoke4khdhub(title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = app.get("$fourkhdhubAPI/?s=$title").document
        val link = document.select("div.card-grid > a").firstOrNull { element ->
            val content = element.selectFirst("div.movie-card-content")?.text()?.lowercase() ?: return@firstOrNull false
            val matchTitle = title?.lowercase()?.let { it in content } ?: true
            val matchYear = year?.toString()?.lowercase()?.let { it in content } ?: true
            matchTitle && matchYear
        }?.attr("href") ?: return
        val doc = app.get("$fourkhdhubAPI$link").document
        if (season == null) {
            doc.select("div.download-item a").safeAmap {
               var source = it.attr("href")
               if (source.contains("hubcloud") || source.contains("hubdrive")) {
               } else {
                    source = getRedirectLinks(source)
               }
               loadSourceNameExtractor(
                    "4Khdhub",
                    source,
                    "",
                    subtitleCallback,
                    callback
                )
            }
        } else {
            val (seasonText, episodeText) = getEpisodeSlug(season, episode)
            doc.select("div.episode-download-item:has(div.episode-file-title:contains(S${seasonText}E${episodeText}))").safeAmap {
                it.select("div.episode-links > a").safeAmap {
                    var source = it.attr("href")
                    if (source.contains("hubcloud") || source.contains("hubdrive")) {
                    } else {
                        source = getRedirectLinks(source)
                    }
                    loadSourceNameExtractor(
                        "4Khdhub",
                        source,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    }

    suspend fun invokeProjectfreetv(title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val query = if (season == null) {
            "$title".replace(" ", "+")
        } else {
            "${title?.replace(" ", "+")}+-+season+$season"
        }
        val seacrhUrl = "$projectfreetvAPI/data/browse/?lang=3&keyword=$query&year=$year&networks=&rating=&votes=&genre=&country=&cast=&directors=&type=&order_by=&page=1&limit=1"
        val searchJson = app.get(seacrhUrl, referer = projectfreetvAPI, timeout = 60L).text
        val searchObject = JSONObject(searchJson)
        val moviesArray = searchObject.getJSONArray("movies")
        if (moviesArray.length() == 0) return
        val id = moviesArray.getJSONObject(0).getString("_id")
        if (id.isEmpty()) return
        val jsonString = app.get("$projectfreetvAPI/data/watch/?_id=$id", referer = projectfreetvAPI, timeout = 60L).text
        val rootObject = JSONObject(jsonString)
        val sourceList = mutableListOf<String>()
        if (rootObject.has("streams")) {
            val streamsArray = rootObject.getJSONArray("streams")
            for (i in 0 until streamsArray.length()) {
                val item = streamsArray.getJSONObject(i)
                val currentEpisode = item.optString("e").toIntOrNull() ?: -1
                if (episode == null || currentEpisode == episode) {
                    val source = item.optString("stream")
                    if (source.isNotEmpty()) {
                        sourceList.add(source)
                    }
                }
            }
        }
        sourceList.safeAmap {
            loadSourceNameExtractor("ProjectFreeTV", it, "", subtitleCallback, callback)
        }
    }

    suspend fun invokeMlsbd(title: String?, year: Int?, season: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val query = "$title $year".createSlug()
        val tag = if (season != null) "[Combined]" else ""
        val url = "$mlsbdAPI/$query"
        val document = app.get(url).document
        val downloadSection = document.selectFirst(".post-section-title.download")
        if (downloadSection?.text() != "Download Now") return
        document.select(".post-content p > a")
            .safeAmap {
                val link = it.attr("href")
                app.get(link).document.select("li > a").safeAmap { source ->
                    loadSourceNameExtractor(
                        "Mlsbd$tag",
                        source.attr("href"),
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            }
    }

    suspend fun invokeLevidia(title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title == null || year == null) return
        val safeTitle = URLEncoder.encode(title, "utf-8")
        val url = if (season == null) {
            "$levidiaAPI/search.php?q=$safeTitle+$year&v=movies"
        } else {
            "$levidiaAPI/search.php?q=$safeTitle+$year&v=episodes"
        }
        val res = app.get(url)
        val sessionId = res.cookies["PHPSESSID"] ?: return
        val regex = Regex("""_3chk\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\)""")
        val match = regex.find(res.text)
        if (match == null) return
        val value1 = match.groupValues[1]
        val value2 = match.groupValues[2]
        val document = res.document
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$levidiaAPI/",
            "Cookie" to "PHPSESSID=$sessionId;$value1=$value2"
        )
        val href = document.select("li.mlist div.mainlink a").firstNotNullOfOrNull { aTag ->
            val parsedTitle = aTag.selectFirst("strong")?.text()?.trim()
            ?: return@firstNotNullOfOrNull null
            val parsedYear = aTag.ownText().replace(Regex("""[^\d]"""), "").toIntOrNull()
            if (parsedTitle.equals(title, ignoreCase = true) && parsedYear == year) {
                aTag.attr("href")
            } else {
                null
            }
        } ?: return
        val doc = app.get(href, headers = headers).document
        if (season == null) {
            doc.select("a.xxx").safeAmap {
                val embedUrl = app.get(
                    it.attr("href"),
                    headers = headers,
                    allowRedirects = false
                ).headers["Location"] ?: return@safeAmap
                loadSourceNameExtractor("Levidia", embedUrl, "$levidiaAPI/", subtitleCallback, callback)
            }
        } else {
            val epRegex = Regex("""(?i)[^a-z]s0?${season}e0?${episode}[^0-9]""")
            val episodePath = doc.select("li.mlist.links b a").firstNotNullOfOrNull { aTag ->
                val href = aTag.attr("href")
                if (epRegex.containsMatchIn(href)) {
                    href
                } else {
                    null
                }
            } ?: return
            val doc2 = app.get("$levidiaAPI/" + episodePath, headers = headers).document
            doc2.select("a.xxx").safeAmap {
                val embedUrl = app.get(
                    it.attr("href"),
                    headers = headers,
                    allowRedirects = false
                ).headers["Location"] ?: return@safeAmap
                 loadSourceNameExtractor("Levidia", embedUrl, "$levidiaAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeM4ufree(title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title == null || year == null) return
        val searchQuery = if (season == null) {
            "${getUrlTitle(title)}-${year}?type=movie"
        } else {
            "${getUrlTitle(title)}-${year}?type=tvs"
        }
        val searchDoc = app.get("$m4ufreeAPI/search/$searchQuery").document
        val matchedHref = searchDoc.select(".item > a").firstOrNull { element ->
            val name = element.attr("title").ifEmpty { element.text() }
            name.contains("$title ($year", ignoreCase = true) || name.contains("$title $year", ignoreCase = true)
        }?.attr("href") ?: return
        val link = fixUrl(matchedHref, m4ufreeAPI)
        val request = app.get(link)
        val doc = request.document
        val cookies = request.cookies
        val token = doc
            .selectFirst("meta[name=csrf-token]")
            ?.attr("content")
        if (token.isNullOrBlank()) return
        val m4uData = if (season == null && episode == null) {
            doc.selectFirst("span.singlemv.active, span#fem")
                ?.attr("data")
        } else {
            val epCode = "S%02d-E%02d".format(season, episode)
            val episodeBtn = doc.select("button.episode")
                .firstOrNull {
                    it.text().trim().equals(epCode, true)
                } ?: return
            val idepisode = episodeBtn.attr("idepisode")
            if (idepisode.isBlank()) return
            val embed = app.post(
                "$m4ufreeAPI/ajaxtv",
                data = mapOf(
                    "idepisode" to idepisode,
                    "_token" to token
                ),
                referer = link,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                cookies = cookies
            ).document
            embed.selectFirst("span.singlemv.active, span#fem")
                    ?.attr("data")
        }
        if (m4uData.isNullOrBlank()) return
        val iframe = app.post(
            "$m4ufreeAPI/ajax",
            data = mapOf(
                "m4u" to m4uData,
                "_token" to token
            ),
            referer = link,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            ),
            cookies = cookies
        ).document
            .selectFirst("iframe")
            ?.attr("src")
        if (iframe.isNullOrBlank()) return
        loadSourceNameExtractor(
            "M4uhd",
            fixUrl(iframe, link),
            m4ufreeAPI,
            subtitleCallback,
            callback
        )
    }

    suspend fun invokeMultimovies(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$multimoviesAPI/movies/$fixTitle"
        } else {
            "$multimoviesAPI/episodes/$fixTitle-${season}x${episode}"
        }
        val req = app.get(url).document
        req.select("ul#playeroptionsul li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.safeAmap { (id, nume, type) ->
            if (!nume.contains("trailer")) {
                val source = app.post(
                    url = "$multimoviesAPI/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<ResponseHash>().embed_url
                val link = source.substringAfter("\"").substringBefore("\"")
                when {
                    !link.contains("youtube") -> {
                        loadSourceNameExtractor("Multimovies", link, referer = multimoviesAPI, subtitleCallback, callback)
                    }
                    else -> ""
                }
            }
        }
    }

    suspend fun invokeAkwam(imdbId: String?, title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        suspend fun getLink(url: String) : String? {
            val link = app.get(url, referer = "$akwamAPI/")
            .document
            .selectFirst("a.link-download")
            ?.attr("href")
            ?: return null
            val link2 = app.get(link, referer = "$akwamAPI/")
                .document
                .selectFirst("a.download-link")
                ?.attr("href")
                ?: return null
            val source = app.get(link2, referer = "$akwamAPI/")
                .document
                .selectFirst("a.link")
                ?.attr("href")
                ?: return null
            return source
        }
        if (imdbId == null || title == null || year == null) return
        val type = if (season == null) "movie" else "series"
        val searchUrl = "$akwamAPI/search?q=${URLEncoder.encode(title, "UTF-8")}&section=$type&year=$year&rating=0&formats=0&quality=0"
        val url = app.get(searchUrl, referer = "$akwamAPI/")
            .document
            .selectFirst("a.box")
            ?.attr("href")
            ?: return
        val document = app.get(url, referer = "$akwamAPI/").document
        val imdb = document.selectFirst("a[href*='imdb.com']")
            ?.attr("href")
            ?.substringAfter("title/")
            ?.substringBefore("/")
            ?: return
        if (imdbId != imdb) return
        val source = if (season == null) {
            getLink(url)
        } else {
            val episodeLinks = document.select("h2 > a.text-white")
            val match = episodeLinks.find { element ->
                val text = element.text()
                val regex = "(?:حلقة|Episode)\\s+$episode(?!\\d)".toRegex(RegexOption.IGNORE_CASE)
                regex.containsMatchIn(text)
            }
            if (match == null) return
            getLink(match.attr("href"))
        }
        if (source == null) return
        callback.invoke(
            newExtractorLink(
                "Akwam 🇸🇦",
                "Akwam 🇸🇦",
                source,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P720.value
                this.referer = "$akwamAPI/"
                this.headers = mapOf(
                    "Connection" to "keep-alive",
                    "Referer" to "$akwamAPI/",
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }

    suspend fun invokeRtally(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        fun getStreamUrl(
            id: String,
            service: String
        ): String? {
            if (service == "vidhide") return "https://vidhideplus.com/v/$id"
            else if (service == "lulustream") return "https://lulustream.com/e/$id"
            else if (service == "filemoon") return "https://filemoon.sx/e/$id"
            else if (service == "streamwish") return "https://playerwish.com/e/$id"
            else if (service == "strmup") return "https://strmup.cc/$id"
            else return null
        }
        if (season != null) return
        val slugTitle = title.createSlug()
        val url = "$rtallyAPI/post/$slugTitle"
        val doc = app.get(url).document
        val linkPattern = Regex("""\\"(small|medium|large|extraLarge)\\":\\"(https?://[^\\"]+)""")
        val sourceList = mutableListOf<String>()
        linkPattern.findAll(doc.toString()).forEach { match ->
            val durl = match.groupValues[2]
            if (durl.isNotEmpty()) sourceList.add(durl)
        }
        val streamPattern = Regex("""\\"(lulustream|strmup|filemoon|turbo|vidhide|doodStream|streamwish)Url\\":\\"?([^\\"]+)""")
        streamPattern.findAll(doc.toString()).forEach { match ->
            val service = match.groupValues[1]
            val id = match.groupValues[2]
            if (id != "null") {
                val eurl = getStreamUrl(id, service) ?: return@forEach
                if (eurl.isNotEmpty()) sourceList.add(eurl)
            }
        }
        sourceList.safeAmap { loadSourceNameExtractor("Rtally", it, "", subtitleCallback, callback) }
    }

    suspend fun invokeAsiaflix(title: String?, season: Int?, episode: Int?, year: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title == null) return
        if (season != null && season != 1) return
        val searchUrl = "https://api.asiaflix.net/v1/drama/search?q=$title"
        val headers = mapOf(
            "Referer" to asiaflixAPI,
            "X-Access-Control" to "web"
        )
        val jsonString = cfGet(searchUrl, headers).text
        val jsonObject = JSONObject(jsonString)
        val bodyArray = jsonObject.getJSONArray("body")
        var matchedId: String? = null
        var matchedName: String? = null
        for (i in 0 until bodyArray.length()) {
            val item = bodyArray.getJSONObject(i)
            val name = item.getString("name")
            if (title in name) {
                matchedId = item.getString("_id")
                matchedName = name
                break
            }
        }
        val sourceList = mutableListOf<String>()
        if (matchedId != null && matchedName != null) {
            val titleSlug = matchedName.replace(" ", "-")
            val episodeUrl = "$asiaflixAPI/play/$titleSlug-1/$matchedId/1"
            val scriptText = app.get(episodeUrl).document.selectFirst("script#ng-state")?.data() ?: return
            val fullRegex = Regex("""\"number\"\s*:\s*${episode ?: 1}\b[\s\S]*?\"streamUrls\"\s*:\s*(\[[\s\S]*?])""")
            val epJson = fullRegex.find(scriptText)?.groupValues?.get(1) ?: return
            val urlRegex = Regex("""\"url\"\s*:\s*\"(.*?)\"""")
            urlRegex.findAll(epJson).forEach { match ->
                val source = httpsify(match.groupValues[1])
                if (source.isNotEmpty()) sourceList.add(source)
            }
        }
        sourceList.safeAmap {
            loadSourceNameExtractor("Asiaflix", it, "", subtitleCallback, callback)
        }
    }

    suspend fun invokeSkymovies(title: String?, year: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = """$skymoviesAPI/search.php?search=${URLEncoder.encode("$title ($year)", "UTF-8")}&cat=All"""
        val (sSlug, eSlug) = getEpisodeSlug(1, episode)
        app.get(url).document.select("div.L a").safeAmap {
            val titleText = it.text()
            val titleLink = it.attr("href")
            if (!titleText.trim().startsWith("$title ($year)")) return@safeAmap
            val regex = Regex("""S\d{2}E\d{2}""", RegexOption.IGNORE_CASE)
            var singleEpEntry = false
            if (episode != null && regex.containsMatchIn(it.text())) {
                val currentEpRegex = Regex(
                    """E$eSlug""",
                    RegexOption.IGNORE_CASE
                )
                if (!currentEpRegex.containsMatchIn(it.text())) {
                    return@safeAmap
                } else {
                    singleEpEntry = true
                }
            }
            app.get(skymoviesAPI + titleLink).document.select("div.Bolly > a").safeAmap {
                val text = it.text()
                if (episode == null || singleEpEntry) {
                  loadSourceNameExtractor(
                        "Skymovies",
                        it.attr("href"),
                        "",
                        subtitleCallback,
                        callback,
                    )
                } else if (text.contains("Episode")) {
                    if (text.contains("Episode $eSlug")) {
                        loadSourceNameExtractor(
                            "Skymovies",
                            it.attr("href"),
                            "",
                            subtitleCallback,
                            callback,
                        )
                    }
                } else {
                    loadSourceNameExtractor(
                        "Skymovies(Combined)",
                        it.attr("href"),
                        "",
                        subtitleCallback,
                        callback,
                    )
                }
            }
        }
    }

    suspend fun invokeHdmovie2(title: String?, year: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT
        )
        val document = app.get("$hdmovie2API/movies/${title.createSlug()}-$year", headers = headers, allowRedirects = true).document
        val ajaxUrl = "$hdmovie2API/wp-admin/admin-ajax.php"
        val commonHeaders = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest"
        )
        suspend fun String.getIframe(): String = Jsoup.parse(this).select("iframe").attr("src")
        suspend fun fetchSource(post: String, nume: String, type: String): String {
            val response = app.post(
                url = ajaxUrl,
                data = mapOf(
                "action" to "doo_player_ajax",
                "post" to post,
                "nume" to nume,
                "type" to type
            ),
            referer = hdmovie2API,
            headers = commonHeaders
            ).parsed<ResponseHash>()
            return response.embed_url.getIframe()
        }
        var link = ""
        if (episode != null) {
            document.select("ul#playeroptionsul > li").getOrNull(1)?.let { ep ->
                val post = ep.attr("data-post")
                val nume = (episode + 1).toString()
                link = fetchSource(post, nume, "movie")
        }
        } else {
            document.select("ul#playeroptionsul > li")
                .firstOrNull { it.text().contains("v2", ignoreCase = true) }
                ?.let { mv ->
                    val post = mv.attr("data-post")
                    val nume = mv.attr("data-nume")
                    link = fetchSource(post, nume, "movie")
                }
        }
        val (sSlug, eSlug) = getEpisodeSlug(1, episode)
        if (link.isEmpty()) {
            document.select("a[href*=dwo]").safeAmap { anchor ->
                val anchorText = anchor.text()
                val type = if (episode != null && !anchorText.contains("ep", ignoreCase = true)) {
                    " (Combined)"
                } else {
                    ""
                }
                if (episode != null && type == "" && !anchorText.contains("ep$eSlug", ignoreCase = true)) {
                    return@safeAmap
                }
                val innerDoc = app.get(anchor.attr("href")).document
                innerDoc.select("div > p > a").safeAmap {
                    loadSourceNameExtractor(
                        "Hdmovie2$type",
                        it.attr("href"),
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
        if (link.isNotEmpty()) {
            loadSourceNameExtractor(
                "Hdmovie2",
                link,
                hdmovie2API,
                subtitleCallback,
                callback,
            )
        }
    }

    suspend fun invokeMostraguarda(id: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = "$MostraguardaAPI/movie/$id"
        val doc = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
        ).document
        doc.select("ul > li").safeAmap {
            if (it.text().contains("supervideo")) {
                val source = "https:" + it.attr("data-link")
                SuperVideo().getUrl(source, "", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeOnetouchtv(title: String?, airedYear: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title == null || airedYear == null) return
        var query = title
        if (season != null && season != 1) {
            query += " Season $season ($airedYear)"
        } else {
            query += " ($airedYear)"
        }
        val encrypt = app.get("$onetouchtvAPI/vod/search?page=1&keyword=$query").text
        val decrypt = app.post(
            "$multiDecryptAPI/dec-onetouchtv",
            json = mapOf("text" to encrypt)
        ).text
        val result = JSONObject(decrypt).getJSONArray("result").toString()
        val mediaItems: List<OneMediaItem> = parseJson<List<OneMediaItem>>(result)
        val matchedId = mediaItems.firstOrNull { it.title.equals(query, ignoreCase = true) }?.id ?: return
        val encodeSource = app.get("$onetouchtvAPI/web/vod/$matchedId/episode/${episode ?: 0}").text
        val decryptSource = app.post(
            "$multiDecryptAPI/dec-onetouchtv",
            json = mapOf("text" to encodeSource)
        ).text
        val sourceResult = JSONObject(decryptSource).getJSONObject("result").toString()
        val playbackData = parseJson<OnePlaybackData>(sourceResult)
        playbackData.sources.forEach { source ->
            val type = if (source.type == "hls") ExtractorLinkType.M3U8 else INFER_TYPE
            val quality = getIndexQuality(source.quality)
            callback.invoke(
                newExtractorLink(
                    "Onetouchtv",
                    "Onetouchtv",
                    source.url,
                    type
                ) {
                    this.headers = source.headers ?: emptyMap()
                    this.quality = quality
                }
            )
        }
        playbackData.track.forEach { subtitle ->
            mySubtitleCallback(subtitle.name, subtitle.file, subtitleCallback, "Onetouchtv")
        }
    }

    suspend fun invokeKisskh(title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val slug = title.createSlug() ?: return
        val type = if (season == null) "2" else "1"
        val searchResponse = app.get(
            "$kissKhAPI/api/DramaList/Search?q=$title&type=$type",
            referer = "$kissKhAPI/"
        )
        if (searchResponse.code != 200) return
        val res = tryParseJson<ArrayList<KisskhResults>>(searchResponse.text) ?: return
        val (id, contentTitle) = if (res.size == 1) {
            res.first().id to res.first().title
        } else {
            val data = res.find {
                val slugTitle = it.title.createSlug() ?: return@find false
                val tSlug = it.title?.createSlug() ?: return@find false
                val tActual = it.title
                when (season) {
                    null -> tSlug == slug
                    1 -> tSlug == slug || (tSlug.contains(slug) && (tActual.contains("$year") || tActual.contains("Season 1", true)))
                    else -> tSlug.contains(slug) && tActual.contains("Season $season", true)
                }
            } ?: res.find { it.title.equals(title, true) }
            data?.id to data?.title
        }
        val detailResponse = app.get(
            "$kissKhAPI/api/DramaList/Drama/$id?isq=false",
            referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}?id=$id"
        )
        if (detailResponse.code != 200) return
        val resDetail = detailResponse.parsedSafe<KisskhDetail>() ?: return
        val epsId =
            if (season == null) resDetail.episodes?.first()?.id else resDetail.episodes?.find { it.number == episode }?.id
                ?: return
        val epJson = app.get("$multiDecryptAPI/enc-kisskh?text=$epsId&type=vid", referer = kissKhAPI).text
        val vid_key = JSONObject(epJson).getString("result")
        val sourcesResponse = app.get(
            "$kissKhAPI/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$vid_key",
            referer = kissKhAPI
        )
        if (sourcesResponse.code != 200) return
        sourcesResponse.parsedSafe<KisskhSources>()?.let { source ->
            listOf(source.video, source.thirdParty).safeAmap { link ->
                val safeLink = link ?: return@safeAmap null
                when {
                    safeLink.contains(".m3u8") || safeLink.contains(".mp4") -> {
                        callback.invoke(
                            newExtractorLink(
                                "Kisskh",
                                "Kisskh",
                                fixUrl(safeLink, kissKhAPI),
                                INFER_TYPE
                            ) {
                                referer = kissKhAPI
                                quality = Qualities.P720.value
                                headers = mapOf("Origin" to kissKhAPI)
                            }
                        )
                    }
                    else -> {
                        val cleanedLink = safeLink.substringBefore("?").takeIf { it.isNotBlank() }
                            ?: return@safeAmap null
                        loadSourceNameExtractor(
                            "Kisskh",
                            fixUrl(cleanedLink, kissKhAPI),
                            "$kissKhAPI/",
                            subtitleCallback,
                            callback,
                            Qualities.P720.value
                        )
                    }
                }
            }
        }
        val subJson = app.get("$multiDecryptAPI/enc-kisskh?text=$epsId&type=sub").text
        val sub_key = JSONObject(subJson).getString("result")
        val subResponse = app.get("$kissKhAPI/api/Sub/$epsId?kkey=$sub_key", referer = kissKhAPI)
        if (subResponse.code != 200) return
        tryParseJson<List<KisskhSubtitle>>(subResponse.text)?.forEach { sub ->
            mySubtitleCallback(sub.label ?: return@forEach, sub.src ?: return@forEach, subtitleCallback, "Kisskh")
        }
    }

    suspend fun invokeToonstream(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) {
            "$toonStreamAPI/movies/${title.createSlug()}/"
        } else {
            "$toonStreamAPI/episode/${title.createSlug()}-${season}x${episode}/"
        }
        app.get(url, referer = toonStreamAPI).document.select("div.video > iframe").safeAmap {
            val source = it.attr("data-src")
            val doc = app.get(source).document
            doc.select("div.Video > iframe").safeAmap { iframe ->
                loadSourceNameExtractor(
                    "ToonStream",
                    iframe.attr("src"),
                    "$toonStreamAPI/",
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    suspend fun invokeAnimekizz(title: String?, aniId: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (aniId == null || title == null) return
        val encodedTitle = title.replace(" ", "-")
        val query = "${encodedTitle}-${aniId}:${episode ?: 1}"
        val serversJson = try {
            app.get(
                "$animekizzAPI/api/v1/video/servers/$query",
                referer = "$animekizzAPI/"
            ).text
        } catch (e: Exception) {
            return
        }
        val serversArray = try {
            JSONObject(serversJson).optJSONArray("servers") ?: return
        } catch (e: Exception) {
            return
        }
        for (i in 0 until serversArray.length()) {
            val serverObj = serversArray.optJSONObject(i) ?: continue
            val id = serverObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = serverObj.optString("name").capitalizeServer()
            val serverType = serverObj.optString("server_type").capitalizeServer()
            val resolveJson = try {
                app.post(
                    "$animekizzAPI/api/v1/video/resolve",
                    json = mapOf(
                        "episode_id" to query,
                        "server_id" to id,
                    ),
                    referer = "$animekizzAPI/"
                ).text
            } catch (e: Exception) {
                continue
            }
            val sourcesArray = try {
                JSONObject(resolveJson).optJSONArray("sources") ?: continue
            } catch (e: Exception) {
                Log.e("Animekizz", "Unable to parse resolve response for server $name")
                continue
            }
            for (j in 0 until sourcesArray.length()) {
                val sourceObj = sourcesArray.optJSONObject(j) ?: continue
                var streamUrl = sourceObj.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (streamUrl.startsWith("/proxy/")) streamUrl = animekizzAPI + streamUrl
                val quality = sourceObj.optString("quality", "Unknown")
                val format = sourceObj.optString("format", "Unknown")
                callback.invoke(
                    newExtractorLink(
                        "Animekizz [$name] [$serverType]",
                        "Animekizz [$name] [$serverType]",
                        streamUrl,
                        if (format.equals("hls", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.quality = if (quality == "auto") Qualities.P1080.value else getIndexQuality(quality)
                        this.headers = mapOf(
                            "Referer" to "$animekizzAPI/",
                            "Origin" to animekizzAPI
                        )
                    }
                )
            }
        }
    }

    suspend fun invokeAnimesalt(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val slug = title?.createSlug()
        val headers = mapOf(
            "Referer" to "$animesaltAPI/",
            "User-Agent" to USER_AGENT
        )
        val url = if (season == null) {
            "$animesaltAPI/movies/$slug/"
        } else {
            "$animesaltAPI/episode/$slug-${season}x${episode}/"
        }
        val html = app.get(url, headers = headers).text
        val iframeMatch = Regex("""src="(https://as-cdn\d+\.top/video/([a-f0-9]+))\"""")
            .find(html) ?: return
        val playerUrl = iframeMatch.groupValues[1]
        val hash = iframeMatch.groupValues[2]
        val playerCdn = playerUrl.split("/video/")[0]
        val data = app.post(
            "$playerCdn/player/index.php?data=$hash&do=getVideo",
            requestBody = "hash=$hash&r=${URLEncoder.encode("$animesaltAPI/", "UTF-8")}"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType()),
            headers = mapOf(
                "Referer" to "$animesaltAPI/",
                "Origin" to playerCdn,
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<AnimeSaltData>() ?: return
        val m3u8 = data.videoSource ?: data.securedLink ?: return
        callback.invoke(
            newExtractorLink(
                "AnimeSalt[Multi]",
                "AnimeSalt[Multi]",
                m3u8,
                ExtractorLinkType.M3U8
            ) {
                this.headers = headers
                this.quality = Qualities.P1080.value
            }
        )
    }

    suspend fun invokeZinkmovies(title: String?, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val searchDoc = app.get("$zinkmoviesAPI/?s=${title} ${year}").document
        val typeSpan = if (season != null) "span.tvshows" else "span.movies"
        val matchUrls = searchDoc.select("div.result-item article")
            .filter { article ->
                article.selectFirst(typeSpan) != null &&
                article.selectFirst("div.title a")?.text()
                    ?.contains(title ?: "", ignoreCase = true) == true &&
                (year == null || article.selectFirst("span.year")?.text() == year.toString())
            }
            .mapNotNull { it.selectFirst("div.title a")?.attr("href") }
        if (matchUrls.isEmpty()) return
        matchUrls.safeAmap { matchUrl ->
            val detailDoc = app.get(matchUrl).document
            val content = detailDoc.selectFirst("div.wp-content") ?: return@safeAmap
            if (season != null && episode != null) {
                extractSeasonLinks(content, season).safeAmap { seasonBtnUrl ->
                    val episodeDoc = app.get(seasonBtnUrl).document
                    val episodeUrl = episodeDoc.select("a.maxbutton-download-now")
                        .firstOrNull { a ->
                            Regex("""EPISODE\s*-\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                                .find(a.text())?.groupValues?.get(1)?.toIntOrNull() == episode
                        }?.attr("href") ?: return@safeAmap
                    getZinkLinks(episodeUrl, subtitleCallback, callback)
                }
            } else {
                content.select("div.movie-button-container a.movie-simple-button")
                    .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
                    .safeAmap {
                    getZinkLinks(it, subtitleCallback, callback)
                 }
            }
        }
    }

    suspend fun invokeDahmerMovies(title: String?, year: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) {
            "$dahmerMoviesAPI/movies/${title?.replace(":", "")} ($year)/"
        } else {
            "$dahmerMoviesAPI/tvs/${title?.replace(":", " -")}/Season $season/"
        }
        val request = app.get(url, timeout = 60L)
        if (!request.isSuccessful) return
        val paths = request.document.select("a").map {
            it.text() to it.attr("href")
        }.filter {
            if (season == null) {
                it.first.contains(Regex("(?i)(720p|1080p|2160p)"))
            } else {
                val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
                it.first.contains(Regex("(?i)S${seasonSlug}E${episodeSlug}"))
            }
        }.ifEmpty { return }
        paths.safeAmap {
            val quality = getIndexQuality(it.first)
            val tags = getIndexQualityTags(it.first)
            val href = if (it.second.contains(dahmerMoviesAPI)) it.second else (dahmerMoviesAPI + it.second)
            callback.invoke(
                newExtractorLink(
                    "DahmerMovies",
                    "[DahmerMovies]".toSansSerifBold() + " $tags",
                    href,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = dahmerMoviesAPI
                }
            )
        }
    }

    suspend fun invokeAnizone(title: String?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = "$anizoneAPI/anime?search=$title"
        val link = app.get(url).document.select("div.truncate > a").firstOrNull()?.attr("href") ?: return
        val document = app.get("$link/${episode ?: 1}").document
        val subtitles = document.select("track").map {
            mySubtitleCallback(it.attr("label"), it.attr("src"), subtitleCallback, "Anizone")
        }
        val source = document.select("media-player").attr("src")
        callback.invoke(
            newExtractorLink(
                "Anizone",
                "Anizone Multi Audio 🌐",
                source,
                type = ExtractorLinkType.M3U8,
            ) {
                this.quality = Qualities.P1080.value
            }
        )
    }

    suspend fun invokeTokyoInsider(title: String?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tvtype = if (episode == null) "_(Movie)" else "_(TV)"
        val firstChar = getFirstCharacterOrZero("$title").uppercase()
        val newTitle = title?.replace(" ", "_")
        val doc = app.get("$tokyoInsiderAPI/anime/$firstChar/$newTitle$tvtype").document
        val selector = if (episode != null) "a.download-link:matches((?i)(episode $episode\\b))" else "a.download-link"
        val aTag = doc.selectFirst(selector)
        val epUrl = aTag?.attr("href") ?: return
        val res = app.get(tokyoInsiderAPI + epUrl, timeout = 500L).document
        res.select("div.c_h2 > div > a").map {
            val name = it.text()
            val url = it.attr("href")
            callback.invoke(
                newExtractorLink(
                    "TokyoInsider",
                    "[TokyoInsider] - $name",
                    url,
                ) {
                    this.quality = getIndexQuality(name)
                }
            )
        }
    }

    suspend fun invokeAnimetosho(kitsuId: String?, malId: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val id = malId ?: kitsuId?.toIntOrNull() ?: return
        val type = if (malId == null) "kitsu_id" else "mal_id"
        val json = app.get("$anizipAPI/mappings?$type=$id").text
        val epId = getEpAnizipId(json, episode ?: 1) ?: return
        val json2 = app.get("$animetoshoAPI/json/v1/episodes/$epId").text
        val response = parseJson<AnimetoshoResponse>(json2)
        val items = response.data?.releases ?: return
        val sorted = items
            .filter { (it.seeders ?: 0) >= 25 && !it.magnet.isNullOrBlank() }
            .sortedBy { it.sizeBytes ?: Long.MAX_VALUE }
        for (it in sorted) {
            val title = it.title ?: ""
            val s = it.seeders ?: 0
            val l = it.leechers ?: 0
            val magnet = it.magnet ?: continue
            val size = it.sizeBytes ?: 0L
            val sizeStr = formatSize(size)
            val type2 = if (
                title.contains("Dual", ignoreCase = true)
                || title.contains("DUB", ignoreCase = true)
            ) {
                "DUB"
            } else {
                "SUB"
            }
            val simplifiedTitle = getSimplifiedTitle(title + sizeStr)
            val displayTitle = "Animetosho [$type2]".toSansSerifBold() + " 🧲 | ⬆️ $s | ⬇️ $l | $simplifiedTitle"
            callback.invoke(
                newExtractorLink(
                    "Animetosho[$type2]🧲",
                    displayTitle,
                    magnet,
                    ExtractorLinkType.MAGNET,
                ) {
                    this.quality = getIndexQuality(title)
                }
            )
        }
    }

    suspend fun invokeAnimetoshoHttp(title: String?, malId: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title == null || malId == null) return
        val json = app.get("$anizipAPI/mappings?mal_id=$malId").text
        val epId = getEpAnizipId(json, episode ?: 1) ?: return
        val slug = title.createSlug()
        val url = "$animetoshoBaseAPI/episode/$epId"
        val document = app.get(url).document
        document.select("div.home_list_entry").safeAmap {
            val text = it.select("div.link > a").attr("title")
            val size = it.select("div.size").text()
            val quality = getIndexQuality(text)
            val type = if (text.contains("Dual Audio", true) || text.contains("Dub", true)) {
                "DUB"
            } else {
                "SUB"
            }
            it.select("div.links > a").safeAmap { anchor ->
                val href = anchor.attr("href")
                val anchorText = anchor.text()
                if (anchorText.contains("Torrent") || anchorText.contains("Magnet")) return@safeAmap
                loadSourceNameExtractor("Animetosho[$type]", href, "", subtitleCallback, callback, quality, size)
            }
        }
    }

    suspend fun invokeAnimepahe(imdbId: String?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Cookie" to "__ddg2_=1234567890"
        )
        val id = cfGet(imdbId?.replace(".com", ".pw") ?: return, headers).document.selectFirst("meta[property=og:url]")
            ?.attr("content").toString().substringAfterLast("/")
        val animeData =
            cfGet("$animepaheAPI/api?m=release&id=$id&sort=episode_asc&page=1", headers)
                .parsedSafe<animepahe>()?.data
        val session = if (episode == null) {
            animeData?.firstOrNull()?.session ?: return
        } else {
            animeData?.getOrNull(episode - 1)?.session ?: return
        }
        val doc = cfGet("$animepaheAPI/play/$id/$session", headers).document
        runLimitedAsync(concurrency = 2,
            {
                doc.select("div#pickDownload > a").safeAmap {
                    val href = it.attr("href")
                    var type = "SUB"
                    if (it.attr("data-audio") == "Eng") type = "DUB"
                    loadCustomExtractor(
                        "Animepahe [$type]",
                        href,
                        "$animepaheAPI/",
                        subtitleCallback,
                        callback,
                        getIndexQuality(it.text())
                    )
                }
            },
            {
                doc.select("div#resolutionMenu > button").safeAmap {
                    var type = "SUB"
                    if (it.attr("data-audio") == "Eng") type = "DUB"
                    val quality = it.attr("data-resolution")
                    val href = it.attr("data-src")
                    if (href.contains("kwik.cx")) {
                        loadCustomExtractor(
                            "Animepahe(VLC) [$type]",
                            href,
                            "$animepaheAPI/",
                            subtitleCallback,
                            callback,
                            getQualityFromName(quality)
                        )
                    }
                }
            },
        )
    }

    suspend fun invokeAnikage(title: String?, anilistId: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val searchUrl = "$anikageAPI/api/media/anime/browse?q=$title&sort=popularity&page=1&limit=25&adult=true"
        val searchRes = app.get(searchUrl).parsedSafe<AnikageSearch>() ?: return
        val match = searchRes.data?.find { it.anilistId == anilistId } ?: return
        val slug = match.slug ?: return
        val serversUrl = "$anikageAPI/api/media/anime/$slug/episodes/${episode ?: 1}/servers"
        val serversResponse = app.get(serversUrl).text
        val parsed = tryParseJson<AnikageServersResponse>(serversResponse) ?: return
        val serverIds = parsed.servers?.mapNotNull { it.id } ?: return
        val langs = listOf("sub", "dub")
        serverIds.safeAmap { server ->
            langs.safeAmap { lang ->
                val sourceUrl = "$anikageAPI/api/media/anime/$slug/episodes/${episode ?: 1}/sources?provider=$server&lang=$lang"
                val sourceRes = app.get(sourceUrl).parsedSafe<AnikageSource>() ?: return@safeAmap
                sourceRes.sources?.forEach { source ->
                    val encodedUrl = source.url ?: return@forEach
                    val isM3U8 = source.isM3U8 ?: false
                    val proxiedUrl = "https://prox.anikage.cc/${if (isM3U8) "m3u8" else "stream"}/$encodedUrl"
                    callback.invoke(
                        newExtractorLink(
                            "Anikage[${server.capitalizeServer()}] ${lang.capitalizeServer()}",
                            "Anikage[${server.capitalizeServer()}] ${lang.capitalizeServer()}",
                            proxiedUrl,
                            if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = 1080
                            this.referer = "$anikageAPI/"
                        }
                    )
                }
                sourceRes.subtitles?.forEach { sub ->
                    val file = sub.file ?: return@forEach
                    val label = sub.label ?: "Unknown"
                    mySubtitleCallback(label, file, subtitleCallback, "Anikage")
                }
                sourceRes.embeds?.safeAmap { embed ->
                    val embedUrl = embed.url
                    loadSourceNameExtractor("Anikage [${embed.type.capitalizeServer()}]", embedUrl, "$anikageAPI/", subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invokeAnineko(title: String?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val responseText = app.get("$aninekoAPI/ajax/search?q=$title").text
        val parsedData = tryParseJson<AninekoSearchResponse>(responseText)
        val firstMatch = parsedData?.results?.firstOrNull() ?: return
        val showPath = firstMatch.url ?: return
        val epUrl = "$aninekoAPI$showPath/ep-${episode ?: 1}"
        val epDoc = app.get(epUrl).document
        val serverButtons = epDoc.select("button.server-video")
        val vttRegex = Regex("""(https?://[^&"']+\.vtt)""")
        val langRegex = Regex("""(?:sub_1|c1_label)=([^&]+)""")
        serverButtons.safeAmap { button ->
            val rawVideoUrl = button.attr("data-video")
            if (rawVideoUrl.isBlank()) return@safeAmap
            val serverName = button.ownText().trim()
            val type = button.selectFirst("span")?.text()?.trim() ?: "SUB"
            val sourceName = "Anineko $serverName [$type]"
            vttRegex.findAll(rawVideoUrl).forEach { match ->
                val subUrl = match.groupValues[1]
                val langMatch = langRegex.find(rawVideoUrl)
                val lang = langMatch?.groupValues?.get(1) ?: "English"
                mySubtitleCallback(lang, subUrl, subtitleCallback, "Anineko")
            }
            loadCustomExtractor(sourceName, rawVideoUrl, "$aninekoAPI/", subtitleCallback, callback)
        }
    }

    suspend fun invokeAnimedao(imdbTitle: String?, title: String?, year: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var matchedUrl = cfGet("$animedaoAPI/search.html?keyword=${URLEncoder.encode(imdbTitle, "UTF-8")}&year%5B%5D=$year&sort=title_az")
            .document
            .selectFirst("article.an-anime-card > a")
            ?.attr("href")
            ?.replace("/anime/", "/watch-online/")
        if (matchedUrl == null) {
            matchedUrl = cfGet("$animedaoAPI/search?q=${URLEncoder.encode(imdbTitle, "UTF-8")}")
            .document
            .selectFirst("article.an-anime-card > a")
            ?.attr("href")
            ?.replace("/anime/", "/watch-online/")
            ?: return
        }
        val document = app.get(animedaoAPI + matchedUrl + "-episode-${episode ?: 1}", referer = "$animedaoAPI/").document
        document.select("div.an-server-panel").safeAmap { div ->
            val type = div.attr("data-an-panel").capitalizeServer()
            div.select("div.an-server-list > button").safeAmap { button ->
                val rawUrl = button.attr("data-an-video").takeIf { it.isNotBlank() } ?: return@safeAmap
                val server = button.selectFirst("span")?.ownText() ?: ""
                val queryParams: Map<String, String> = rawUrl.substringAfter("?", "")
                    .split("&")
                    .filter { it.contains("=") }
                    .associate<String, String, String> { param ->
                    param.substringBefore("=") to java.net.URLDecoder.decode(
                        param.substringAfter("="), "UTF-8"
                    )
                }
                val subtitleUrl: String? = queryParams["sub"]
                    ?: queryParams["caption_1"]
                    ?: queryParams["c1_file"]
                val subtitleLang: String = queryParams["sub_1"]
                    ?: queryParams["c1_label"]
                    ?: "English"
                if (subtitleUrl != null) mySubtitleCallback(subtitleLang, subtitleUrl, subtitleCallback, "Animedao")
                loadCustomExtractor("Animedao[$type] $server", rawUrl, "$animedaoAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeAnikoto(title: String?, year: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "referer" to "$anikotoAPI/",
            "x-requested-with" to "XMLHttpRequest"
        )
        val document = app.get(
            "$anikotoAPI/filter?keyword=$title&type=&year%5B%5D=$year&ep_min=&ep_max=&sort=default"
        ).document
        val dataTip = document.selectFirst("div.tip.ani")?.attr("data-tip") ?: return
        val infoJson = app.get("$anikotoAPI/ajax/episode/list/$dataTip?vrf=", headers = headers).text
        val infoParsed = tryParseJson<AnikotoResponse>(infoJson) ?: return
        val infoDocument = Jsoup.parse(infoParsed.result)
        val epAnchor = infoDocument.selectFirst("ul.ep-range li a[data-num='$episode']") ?: return
        val dataIds = epAnchor.attr("data-ids")
        val serversJson = app.get("$anikotoAPI/ajax/server/list?servers=$dataIds", headers = headers).text
        val serversParsed = tryParseJson<AnikotoResponse>(serversJson) ?: return
        val serversDocument = Jsoup.parse(serversParsed.result)
        val serverTypes = serversDocument.select("div.servers div.type")
        serverTypes.safeAmap { serverType ->
            val type = serverType.attr("data-type").capitalizeServer()
            val serverList = serverType.select("ul li")
            serverList.safeAmap { server ->
                val serverName = server.text().trim()
                val linkId = server.attr("data-link-id")
                val serverResponseJson = app.get("$anikotoAPI/ajax/server?get=$linkId", headers = headers).text
                val serverResponse = tryParseJson<AnikotoServerResponse>(serverResponseJson) ?: return@safeAmap
                val embedUrl = serverResponse.result?.url ?: return@safeAmap
                loadCustomExtractor("Anikoto[$type]", embedUrl, "$anikotoAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeAnidb(title: String?, year: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val searchUrl = "$anidbAPI/browse?q=$title&type=&status=&season=&year=$year&genres=&sort=order_top"
        val matchedId = app.get(searchUrl).document
            .selectFirst("div.anime-grid > a")
            ?.attr("href")?.substringAfterLast("-")
            ?: return
        val episodes = app.get("$anidbAPI/api/frontend/anime/$matchedId/episodes")
            .parsedSafe<AnidbResponse>() ?: return
        val episodeId = episodes.episodes
            ?.getOrNull((episode ?: 1) - 1)
            ?.id ?: return
        val languages = app.get("$anidbAPI/api/frontend/episode/$episodeId/languages")
            .parsedSafe<AnidbLanguagesResponse>()?.languages ?: return
        languages.forEach { language ->
            val embedUrl = language.embedUrl ?: return@forEach
            val isDub = language.code == "eng"
            val embedDoc = app.get(embedUrl).document
            val videoUrl = Regex("""file:\s*'([^']+)'""").find(embedDoc.html())?.groupValues?.get(1) ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    "Anidb",
                    "Anidb ${if (isDub) "[DUB]" else "[SUB]"}",
                    videoUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = embedUrl
                }
            )
        }
    }

    suspend fun invokeReanime(aniId: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Referer" to "$reanimeAPI/",
            "User-Agent" to USER_AGENT
        )
        val response = cfGet(
            "$reanimeAPI/api/flix/$aniId/${episode ?: 1}",
            headers = headers
        ).parsedSafe<ReanimeResponse>() ?: return
        if (!response.success) return
        response.servers.safeAmap { server ->
            val type = server.dataType.capitalizeServer()
            val dataLink = server.dataLink
            loadCustomExtractor("Reanime[$type]", dataLink, "", subtitleCallback, callback)
        }
    }

    // ─── Generic Donghua scraper ──────────────────────────────────────
    suspend fun invokeDonghuaGeneric(
        sourceName: String,
        baseUrl: String,
        title: String?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (title == null) return
        try {
            val searchQuery = title.replace(" ", "+")
            val searchUrl = "$baseUrl/search?q=$searchQuery"
            val doc = app.get(searchUrl, referer = baseUrl).document
            val animeLink = doc.selectFirst("a[href*=/anime/], a[href*=/watch/], a.anime-title, a.entry-title, h2 a, .post-title a, .title a, .anime-name a")?.attr("href")
                ?: doc.select("a").firstOrNull { it.text().contains(title, ignoreCase = true) }?.attr("href")
                ?: return
            val fullAnimeUrl = fixUrl(animeLink, baseUrl)
            val animePage = app.get(fullAnimeUrl, referer = baseUrl).document
            val epNumber = episode ?: 1
            val epText = "Episode $epNumber"
            val epSelector = "a[href*=/episode-$epNumber/], a:contains($epText), a:contains(Ep $epNumber), a:contains(E$epNumber)"
            var epLink = animePage.selectFirst(epSelector)?.attr("href")
            if (epLink == null) {
                val epList = animePage.select("a[href*=/episode-], a[href*=/watch/]")
                if (epList.isNotEmpty()) {
                    val targetIndex = epNumber - 1
                    epLink = if (targetIndex < epList.size) epList[targetIndex].attr("href") else epList.last().attr("href")
                }
            }
            if (epLink == null) return
            val fullEpUrl = fixUrl(epLink, baseUrl)
            val epPage = app.get(fullEpUrl, referer = baseUrl).document
            val epHtml = epPage.toString()
            val sources = mutableListOf<String>()
            val videoSrc = epPage.selectFirst("video source")?.attr("src")
            if (!videoSrc.isNullOrBlank()) sources.add(videoSrc)
            val iframeSrc = epPage.selectFirst("iframe")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) sources.add(fixUrl(iframeSrc, baseUrl))
            val scriptData = epPage.select("script").joinToString("\n") { it.data() }
            val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
            val mp4Regex = Regex("""https?://[^\s"']+\.mp4[^\s"']*""")
            m3u8Regex.findAll(scriptData).forEach { sources.add(it.value) }
            mp4Regex.findAll(scriptData).forEach { sources.add(it.value) }
            val jsonRegex = Regex("""\{[^{}]*"file"[^{}]*:\s*"([^"]+)"[^{}]*\}""")
            jsonRegex.findAll(epHtml).forEach { match -> val url = match.groupValues[1]; if (url.startsWith("http")) sources.add(url) }
            sources.distinct().forEach { src ->
                val finalUrl = fixUrl(src, baseUrl)
                if (finalUrl.isBlank()) return@forEach
                if (finalUrl.contains("embed") || finalUrl.contains("player")) {
                    loadSourceNameExtractor(sourceName, finalUrl, baseUrl, subtitleCallback, callback)
                    return@forEach
                }
                val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
                callback.invoke(
                    newExtractorLink(
                        sourceName,
                        "$sourceName",
                        finalUrl,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = baseUrl
                        this.quality = if (finalUrl.contains("1080")) Qualities.P1080.value else if (finalUrl.contains("720")) Qualities.P720.value else Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) { Log.e("DonghuaGeneric", "Error: ${e.message}") }
    }

    // ─── Helpers (all) ──────────────────────────────────────────────────
    suspend fun mySubtitleCallback(lang: String?, url: String, subtitleCallback: (SubtitleFile) -> Unit, source: String? = null) {
        subtitleCallback.invoke(newSubtitleFile(lang ?: "Unknown", url))
    }

    suspend fun runLimitedAsync(concurrency: Int = 10, vararg tasks: suspend () -> Unit) = supervisorScope {
        val mutex = Mutex()
        tasks.map { task ->
            async(Dispatchers.IO) {
                mutex.withLock { try { task() } catch (e: Exception) { /* ignore */ } }
            }
        }.awaitAll()
    }

    fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val lower = str.lowercase()
        return when { lower.contains("4k") -> 2160; lower.contains("1080") -> 1080; lower.contains("720") -> 720; else -> Qualities.Unknown.value }
    }

    fun getEpisodeSlug(season: Int?, episode: Int?): Pair<String, String> {
        val s = if (season != null && season < 10) "0$season" else season?.toString() ?: ""
        val e = if (episode != null && episode < 10) "0$episode" else episode?.toString() ?: ""
        return s to e
    }

    fun String.getHost(): String = try { URI(this).host ?: "" } catch (_: Exception) { "" }
    fun String.queryParams(): Map<String, String> = split("&").mapNotNull { val parts = it.split("=", limit = 2); if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null }.toMap()
    fun JSONObject?.toStringMap(): Map<String, String> { val map = mutableMapOf<String, String>(); this?.keys()?.forEach { k -> map[k] = this.optString(k) }; return map }

    suspend fun checkPosterAvailable(posterUrl: String? = null): String? {
        if (posterUrl == null) return null
        return try { val res = app.head(posterUrl); if (res.code == 200) posterUrl else null } catch (_: Exception) { null }
    }

    suspend fun getTvdbData(tvType: String, imdbId: String? = null): ExtractedMediaData? = null

    suspend fun loadSourceNameExtractor(source: String, url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, quality: Int? = null, size: String = "") {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(
                    source,
                    "$source ${link.name}",
                    link.url,
                    link.type
                ) {
                    this.quality = quality ?: link.quality
                    this.referer = link.referer
                    this.headers = link.headers
                }
            )
        }
    }

    suspend fun loadCustomExtractor(name: String? = null, url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, quality: Int? = null, serverName: String = "") {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(
                    name ?: link.source,
                    name ?: link.name,
                    link.url,
                    link.type
                ) {
                    this.quality = quality ?: link.quality
                    this.referer = link.referer
                    this.headers = link.headers
                }
            )
        }
    }

    // ─── Data classes (all) ────────────────────────────────────────────
    data class ExtractedMediaData(val cast: List<ActorData>?, val poster: String?, val background: String?, val logo: String?)
    data class VideoQuality(val url: String, val quality: String)
    data class FileItem(val fid: Long, val file_name: String?, val is_dir: Boolean)
    data class FileListData(val file_list: List<FileItem>?)
    data class ShareLinkData(val link: String?)
    data class FileListResponse(val data: FileListData?)
    data class ShareLinkResponse(val data: ShareLinkData?)
    data class VideoQualityResponse(val html: String?)
    data class MALSyncSites(@JsonProperty("animepahe") val animepahe: HashMap<String?, HashMap<String, String?>>? = hashMapOf())
    data class MALSyncResponses(@JsonProperty("title") val title: String? = null, @JsonProperty("Sites") val sites: MALSyncSites? = null)
    data class AnizipEpisode(@JsonProperty("anidbEid") val anidbEid: Int?, @JsonProperty("episode") val episode: String?)
    data class Anizip(val episodes: Map<String, AnizipEpisode>?)
    data class AnimetoshoRelease(val title: String?, val magnet: String?, val seeders: Int?, val leechers: Int?, @JsonProperty("size_bytes") val sizeBytes: Long?)
    data class AnimetoshoData(val releases: List<AnimetoshoRelease>?)
    data class AnimetoshoResponse(val data: AnimetoshoData?)
    data class AnikageSearch(val data: List<AnikageResult>?)
    data class AnikageResult(val slug: String?, val anilistId: Int?)
    data class AnikageServersResponse(val servers: List<AnikageServer>?)
    data class AnikageServer(val id: String?)
    data class AnikageSource(val sources: List<AnikageStreamSource>?, val subtitles: List<AnikageSub>?, val embeds: List<AnikageEmbed>?)
    data class AnikageStreamSource(val url: String?, val quality: String?, val isM3U8: Boolean?)
    data class AnikageSub(val file: String?, val label: String?)
    data class AnikageEmbed(val url: String, val type: String, val server: String)
    data class ExternalIds(val anilist: Int?, val myanimelist: Int?, val kitsu: Int?)
}

// ─── Extensions ──────────────────────────────────────────────────────────
fun String.capitalizeServer() = replaceFirstChar { it.uppercase() }
fun String.getBaseUrl(): String = try { URI(this).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { this }
fun String.createSlug(): String? = this.filter { it.isWhitespace() || it.isLetterOrDigit() }.trim().replace("\\s+".toRegex(), "-").lowercase()
fun fixUrl(url: String, domain: String): String = when {
    url.startsWith("http") -> url
    url.isEmpty() -> ""
    url.startsWith("//") -> "https:$url"
    url.startsWith('/') -> domain + url
    else -> "$domain/$url"
}
fun base64Decode(str: String) = String(android.util.Base64.decode(str, android.util.Base64.DEFAULT))
fun base64Encode(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
fun base64DecodeArray(str: String) = android.util.Base64.decode(str, android.util.Base64.DEFAULT)
fun httpsify(url: String) = if (url.startsWith("//")) "https:$url" else url
fun getFirstCharacterOrZero(input: String): String {
    val firstChar = input[0]
    return if (!firstChar.isLetter()) "0" else firstChar.toString()
}
fun getQualityFromName(name: String): Int {
    return when {
        name.contains("1080") -> Qualities.P1080.value
        name.contains("720") -> Qualities.P720.value
        name.contains("480") -> Qualities.P480.value
        name.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
fun getSimplifiedTitle(title: String): String = title
fun getIndexQualityTags(str: String?, fullTag: Boolean = false): String {
    return if (fullTag) Regex("(?i)(.*)\\.(?:mkv|mp4|avi)").find(str ?: "")?.groupValues?.get(1)
        ?.trim() ?: str ?: "" else Regex("(?i)\\d{3,4}[pP]\\.?(.*?)\\.(mkv|mp4|avi)").find(
        str ?: ""
    )?.groupValues?.getOrNull(1)
        ?.replace(".", " ")?.trim() ?: str ?: ""
}
fun getKisskhTitle(str: String?): String? {
    return str?.replace(Regex("[^a-zA-Z\\d]"), "-")
}
fun getUrlTitle(str: String?): String {
    if(str.isNullOrBlank()) return ""
    return str.replace(Regex("[^a-zA-Z\\d]"), "-")
}
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "-"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        else -> String.format("%.2f KB", bytes / kb)
    }
}
fun String.toSansSerifBold(): String {
    val builder = StringBuilder()
    for (char in this) {
        val codePoint = when (char) {
            in 'A'..'Z' -> 0x1D5D4 + (char - 'A')
            in 'a'..'z' -> 0x1D5EE + (char - 'a')
            in '0'..'9' -> 0x1D7EC + (char - '0')
            else -> char.code
        }
        builder.append(Character.toChars(codePoint))
    }
    return builder.toString()
}
