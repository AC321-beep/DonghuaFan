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

// Import the SubDecryptor from the same package
import com.kisskh.SubDecryptor

class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.nl"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)

    // Google Script URLs (as used in the working Turkish provider)
    private val kisskhApiBase = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
    private val kisskhSubBase = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

    companion object {
        var philippineCountryCode = 8
        // Decryption keys have been moved to SubDecryptor
    }

    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=2" to "Trending",
        "&type=0&sub=0&country=2&status=0&order=2" to "Latest K-Drama",
        "&type=0&sub=0&country=1&status=0&order=2" to "Latest C-Drama",
        "&type=2&sub=0&country=$philippineCountryCode&status=0&order=2" to "Latest Philippine Movie",
        "&type=2&sub=0&country=2&status=0&order=2" to "Movie Last Update",
        "&type=1&sub=0&country=2&status=0&order=1" to "TVSeries Popular",
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

        // ---- Subtitles (using Google Script key + SubDecryptor) ----
        val subKeyUrl = "$kisskhSubBase${loadData.epsId}&version=2.8.10"
        Log.d("Kisskh", "Subtitle key URL: $subKeyUrl")
        val subtitleKkey = app.get(subKeyUrl, timeout = 10000).parsedSafe<Key>()?.key ?: ""
        Log.d("Kisskh", "Subtitle kkey: '$subtitleKkey'")

        if (subtitleKkey.isBlank()) {
            Log.e("Kisskh", "subtitleKkey is blank - cannot fetch subtitles")
            return true // Not a fatal error, video may still play
        }

        val subApiUrl = "$mainUrl/api/Sub/${loadData.epsId}?kkey=$subtitleKkey"
        Log.d("Kisskh", "Subtitle API URL: $subApiUrl")
        val subtitleResponse = app.get(subApiUrl).text
        Log.d("Kisskh", "Subtitle API response: $subtitleResponse")
        val subtitleList = tryParseJson<List<Subtitle>>(subtitleResponse) ?: emptyList()
        Log.d("Kisskh", "Parsed subtitle list size: ${subtitleList.size}")

        for (sub in subtitleList) {
            val lang = getLanguage(sub.label ?: continue)
            val srcUrl = sub.src ?: continue
            Log.d("Kisskh", "Fetching subtitle from: $srcUrl")
            try {
                val content = app.get(srcUrl).text
                Log.d("Kisskh", "Content length: ${content.length}, first 100 chars: ${content.take(100)}")
                
                // Check if the content looks like plain text (e.g., starts with "WEBVTT" or "1\n00:00:00")
                val isPlainText = content.startsWith("WEBVTT") || 
                                  content.contains(Regex("""^\d+\n\d{2}:\d{2}:\d{2}""", RegexOption.MULTILINE))
                
                val finalContent = if (isPlainText) {
                    Log.d("Kisskh", "Subtitle appears to be plain text, using as-is")
                    content
                } else {
                    Log.d("Kisskh", "Subtitle appears encrypted, decrypting...")
                    SubDecryptor.decryptFullContent(content)
                }
                subtitleCallback.invoke(newSubtitleFile(lang, finalContent))
            } catch (e: Exception) {
                Log.e("Kisskh", "Failed to process subtitle: ${e.message}")
            }
        }

        return true
    }

    // ---------- Data classes ----------
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
