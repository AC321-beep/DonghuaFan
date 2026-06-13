package com.kisskh

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Import the decryption utility
import com.kisskh.SubDecryptor

class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.nl"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)

    // Google Script URLs (video and subtitle key generation)
    private val kisskhApiBase = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
    private val kisskhSubBase = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

    companion object {
        var philippineCountryCode = 8
        // Decryption keys are in SubDecryptor
    }

   override val mainPage = mainPageOf(
    "&type=0&sub=0&country=0&status=0&order=2" to "Trending",
    "&type=0&sub=0&country=1&status=0&order=2" to "Latest Chinese Drama",
    "&type=0&sub=0&country=2&status=0&order=2" to "Latest Korean Drama",
    "&type=2&sub=0&country=$philippineCountryCode&status=0&order=2" to "Latest Philippine Movie",
    "&type=2&sub=0&country=2&status=0&order=2" to "Movie Last Update",
    "&type=3&sub=0&country=0&status=0&order=2" to "Anime Latest Update",
    "&type=4&sub=0&country=0&status=0&order=2" to "Hollywood Last Update",
    "&type=0&sub=0&country=0&status=3&order=2" to "Upcoming"
)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = app.get("$mainUrl/api/DramaList/List?page=$page${request.data}")
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { it.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid JSON response")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (!settingsForProvider.enableAdult && label?.contains("RAW") == true) return null
        return newAnimeSearchResponse(title ?: return null, "$title/$id", TvType.TvSeries) {
            this.posterUrl = thumbnail
            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/api/DramaList/Search?q=$query&type=0", referer = "$mainUrl/").text
        return tryParseJson<ArrayList<Media>>(searchResponse)?.mapNotNull { it.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid JSON response")
    }

    private fun getTitle(str: String) = str.replace(Regex("[^a-zA-Z0-9]"), "-")

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
            }
        } ?: throw ErrorLoadingException("No episodes found")

        return newTvSeriesLoadResponse(
            res.title ?: "Unknown", url,
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

    private fun getLanguage(str: String) = when (str) {
        "Indonesia" -> "Indonesian"
        else -> str
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<Data>(data)

        // ---- Video links (using Google Script key) ----
        val videoKeyUrl = "$kisskhApiBase${loadData.epsId}&version=2.8.10"
        val kkey = app.get(videoKeyUrl, timeout = 10000).parsedSafe<Key>()?.key ?: ""
        if (kkey.isBlank()) {
            Log.e("Kisskh", "Failed to obtain video kkey")
            return false
        }

        val videoApiUrl = "$mainUrl/api/DramaList/Episode/${loadData.epsId}.png?err=false&ts=&time=&kkey=$kkey"
        val videoReferer = "$mainUrl/Drama/${getTitle("${loadData.title}")}/Episode-${loadData.eps}?id=${loadData.id}&ep=${loadData.epsId}&page=0&pageSize=100"

        app.get(videoApiUrl, referer = videoReferer).parsedSafe<Sources>()?.let { source ->
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
                                    this.name,
                                    this.name,
                                    url = fixUrl(link),
                                    INFER_TYPE
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.P720.value
                                }
                            )
                        }
                        link != null -> {
                            loadExtractor(
                                link.substringBefore("=http"),
                                "$mainUrl/",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
        } ?: Log.e("Kisskh", "No video sources found")

        // ---- Subtitles (using Google Script key, passed as URL, decrypted in interceptor) ----
        val subKeyUrl = "$kisskhSubBase${loadData.epsId}&version=2.8.10"
        val subtitleKkey = app.get(subKeyUrl, timeout = 10000).parsedSafe<Key>()?.key ?: ""
        if (subtitleKkey.isBlank()) {
            Log.e("Kisskh", "subtitleKkey is blank - cannot fetch subtitles")
            return true // Not fatal
        }

        val subApiUrl = "$mainUrl/api/Sub/${loadData.epsId}?kkey=$subtitleKkey"
        val subtitleResponse = app.get(subApiUrl).text
        val subtitleList = tryParseJson<List<Subtitle>>(subtitleResponse) ?: emptyList()
        for (sub in subtitleList) {
            val lang = getLanguage(sub.label ?: continue)
            val srcUrl = sub.src ?: continue
            // Pass the URL – decryption happens inside getVideoInterceptor
            subtitleCallback.invoke(newSubtitleFile(lang, srcUrl))
        }

        return true
    }

    // ----------------------------------------------------------------------
    // Interceptor to decrypt subtitle .txt files on the fly (same as Turkish provider)
    // ----------------------------------------------------------------------
    private val CHUNK_REGEX1 by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder().build()
                val response = chain.proceed(request)
                val url = response.request.url.toString()

                if (url.contains(".txt")) {
                    val responseBody = response.body.string()
                    val chunks = responseBody.split(CHUNK_REGEX1)
                        .filter { it.isNotBlank() }
                        .map { it.trim() }

                    val decrypted = chunks.mapIndexed { index, chunk ->
                        if (chunk.isBlank()) return@mapIndexed ""
                        val parts = chunk.split("\n")
                        if (parts.isEmpty()) return@mapIndexed ""

                        val header = parts.first()
                        val text = parts.drop(1)
                        val decryptedLines = text.joinToString("\n") { line ->
                            try {
                                SubDecryptor.decrypt(line)
                            } catch (e: Exception) {
                                "DECRYPT_ERROR:${e.message}"
                            }
                        }
                        listOf(index + 1, header, decryptedLines).joinToString("\n")
                    }.filter { it.isNotEmpty() }.joinToString("\n\n")

                    val newBody = decrypted.toResponseBody(response.body.contentType())
                    return response.newBuilder()
                        .body(newBody)
                        .build()
                }
                return response
            }
        }
    }

    // ---------- Data classes (unchanged) ----------
    data class Data(val title: String?, val eps: Int?, val id: Int?, val epsId: Int?)
    data class Sources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    data class Subtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)
    data class Responses(@JsonProperty("data") val data: ArrayList<Media>? = arrayListOf())
    data class Media(val episodesCount: Int?, val thumbnail: String?, val label: String?, val id: Int?, val title: String?)
    data class Episodes(val id: Int?, val number: Double?, val sub: Int?)
    data class MediaDetail(
        val description: String?, val releaseDate: String?, val status: String?, val type: String?,
        val country: String?, val episodes: ArrayList<Episodes>? = arrayListOf(), val thumbnail: String?,
        val id: Int?, val title: String?
    )
    data class Key(val id: String, val version: String, val key: String)
}
