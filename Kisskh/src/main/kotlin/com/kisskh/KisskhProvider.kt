package com.phisher98  // or your package name

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.nl"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Anime
    )

    // Philippine country code – adjust if needed (try 5,6,7,0)
    companion object {
        var philippineCountryCode = 5
    }

    // UPDATED main page as requested
    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=2" to "Trending",
        "&type=0&sub=0&country=2&status=0&order=2" to "Latest K-Drama",
        "&type=0&sub=0&country=1&status=0&order=2" to "Latest C-Drama",
        "&type=2&sub=0&country=2&status=0&order=1" to "Movie Popular",
        "&type=2&sub=0&country=2&status=0&order=2" to "Movie Last Update",
        "&type=1&sub=0&country=2&status=0&order=1" to "TVSeries Popular",
        "&type=1&sub=0&country=2&status=0&order=2" to "TVSeries Last Update",
        // Anime Popular removed – only keep Latest Update
        "&type=3&sub=0&country=0&status=0&order=2" to "Anime Latest Update",
        "&type=4&sub=0&country=0&status=0&order=1" to "Hollywood Popular",
        "&type=4&sub=0&country=0&status=0&order=2" to "Hollywood Last Update",
        "&type=0&sub=0&country=0&status=3&order=2" to "Upcoming",
        "&type=2&sub=0&country=$philippineCountryCode&status=0&order=2" to "Latest Philippine Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = app.get("$mainUrl/api/DramaList/List?page=$page${request.data}")
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { media -> media.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid JSON response")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (!settingsForProvider.enableAdult && this.label?.contains("RAW") == true) return null
        return newAnimeSearchResponse(
            title ?: return null,
            "$title/$id",
            TvType.TvSeries,
        ) {
            this.posterUrl = thumbnail
            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/api/DramaList/Search?q=$query&type=0", referer = "$mainUrl/").text
        return tryParseJson<ArrayList<Media>>(searchResponse)?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid JSON response")
    }

    private fun getTitle(str: String): String = str.replace(Regex("[^a-zA-Z0-9]"), "-")

    override suspend fun load(url: String): LoadResponse? {
        val id = url.split("/")
        val res = app.get(
            "$mainUrl/api/DramaList/Drama/${id.last()}?isq=false",
            referer = "$mainUrl/Drama/${getTitle(id.first())}?id=${id.last()}"
        ).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Invalid JSON response")

        val episodes = res.episodes?.map { eps ->
            newEpisode(Data(res.title, eps.number?.toInt(), res.id, eps.id).toJson()) {
                this.name = "Episode ${eps.number?.toInt() ?: "?"}"
                this.episode = eps.number?.toInt()
                // No TMDB data – use only what Kisskh provides
            }
        } ?: throw ErrorLoadingException("No episodes found")

        return newTvSeriesLoadResponse(
            res.title ?: "Unknown",
            url,
            if (res.type == "Movie" || episodes.size == 1) TvType.Movie else TvType.TvSeries,
            episodes.reversed()
        ) {
            this.posterUrl = res.thumbnail
            this.backgroundPosterUrl = res.thumbnail
            this.year = res.releaseDate?.split("-")?.first()?.toIntOrNull()
            this.plot = res.description
            this.tags = listOfNotNull(res.country, res.status, res.type)
            this.showStatus = when (res.status) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    private fun getLanguage(str: String): String = when (str) {
        "Indonesia" -> "Indonesian"
        else -> str
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Note: BuildConfig values must exist or replace with hardcoded URLs
        val kisskhAPI = BuildConfig.KissKh
        val kisskhSub = BuildConfig.KisskhSub
        val loadData = parseJson<Data>(data)

        val kkey = app.get("$kisskhAPI${loadData.epsId}&version=2.8.10", timeout = 10000)
            .parsedSafe<Key>()?.key ?: ""

        app.get(
            "$mainUrl/api/DramaList/Episode/${loadData.epsId}.png?err=false&ts=&time=&kkey=$kkey",
            referer = "$mainUrl/Drama/${getTitle(loadData.title ?: "")}/Episode-${loadData.eps}?id=${loadData.id}&ep=${loadData.epsId}&page=0&pageSize=100"
        ).parsedSafe<Sources>()?.let { source ->
            listOf(source.video, source.thirdParty).amap { link ->
                safeApiCall {
                    when {
                        link?.contains(".m3u8") == true -> {
                            M3u8Helper.generateM3u8(
                                this.name,
                                fixUrl(link),
                                referer = "$mainUrl/",
                                headers = mapOf("Origin" to mainUrl)
                            ).forEach(callback)
                        }
                        link?.contains("mp4") == true -> {
                            callback.invoke(
                                newExtractorLink(
                                    this.name, this.name, fixUrl(link), INFER_TYPE
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.P720.value
                                }
                            )
                        }
                        else -> {
                            loadExtractor(
                                link?.substringBefore("=http") ?: return@safeApiCall,
                                "$mainUrl/",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
        }

        // Subtitles
        val kkey1 = app.get("$kisskhSub${loadData.epsId}&version=2.8.10", timeout = 10000)
            .parsedSafe<Key>()?.key ?: ""
        app.get("$mainUrl/api/Sub/${loadData.epsId}?kkey=$kkey1").text.let { res ->
            tryParseJson<List<Subtitle>>(res)?.map { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        getLanguage(sub.label ?: return@map),
                        sub.src ?: return@map
                    )
                )
            }
        }

        return true
    }

    // Subtitle decryption interceptor (unchanged)
    private val CHUNK_REGEX1 by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request().newBuilder().build()
            val response = chain.proceed(request)
            if (response.request.url.toString().contains(".txt")) {
                val responseBody = response.body.toString()
                val chunks = responseBody.split(CHUNK_REGEX1).filter(String::isNotBlank).map(String::trim)
                val decrypted = chunks.mapIndexed { index, chunk ->
                    if (chunk.isBlank()) return@mapIndexed ""
                    val parts = chunk.split("\n")
                    if (parts.isEmpty()) return@mapIndexed ""
                    val header = parts.first()
                    val text = parts.drop(1)
                    val d = text.joinToString("\n") { line ->
                        try { decrypt(line) } catch (e: Exception) { "DECRYPT_ERROR:${e.message}" }
                    }
                    listOf(index + 1, header, d).joinToString("\n")
                }.filter { it.isNotEmpty() }.joinToString("\n\n")
                val newBody = decrypted.toResponseBody(response.body.contentType())
                return@Interceptor response.newBuilder().body(newBody).build()
            }
            response
        }
    }

    // Data classes
    data class Data(val title: String?, val eps: Int?, val id: Int?, val epsId: Int?)
    data class Sources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    data class Subtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)
    data class Responses(@JsonProperty("data") val data: ArrayList<Media>? = arrayListOf())
    data class Media(
        @JsonProperty("episodesCount") val episodesCount: Int?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?
    )
    data class Episodes(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?, @JsonProperty("sub") val sub: Int?)
    data class MediaDetail(
        @JsonProperty("description") val description: String?,
        @JsonProperty("releaseDate") val releaseDate: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?
    )
    data class Key(val id: String, val version: String, val key: String)
}
