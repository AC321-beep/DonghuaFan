package com.anichi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class AnichiProvider : MainAPI() {
    override var name = "Anichi"
    override var mainUrl = "https://allanimenews.com"
    override var lang = "en"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val API_URL = "https://api.allanime.day/api"
        private const val MAIPAGESHA_HASH = "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
        private const val SERVER_HASH = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"

        val DEFAULT_HEADERS = mapOf(
            "app-version" to "android_c-247",
            "from-app" to "4DqMXoovyMEkBc7H",
            "platformstr" to "android_c",
            "Referer" to "https://allmanga.to",
            "User-Agent" to "Mozilla/5.0"
        )
    }

    suspend fun getStreams(
        tmdbId: String,
        mediaType: String = "tv",
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val tmdbUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY"
            val tmdbResponse = app.get(tmdbUrl).parsedSafe<TmdbResponse>() ?: return false
            val title = tmdbResponse.title ?: tmdbResponse.name ?: return false

            val encodedQuery = URLEncoder.encode(title, "UTF-8")
            val searchVariables = """{"search":{"query":"$encodedQuery"},"limit":26,"page":1,"translationType":"sub","countryOrigin":"ALL"}"""
            val searchExtensions = """{"persistedQuery":{"version":1,"sha256Hash":"$MAIPAGESHA_HASH"}}"""
            val searchUrl = "$API_URL?variables=$searchVariables&extensions=$searchExtensions"

            val searchResText = app.get(searchUrl, headers = DEFAULT_HEADERS).text
            if (searchResText.contains("PERSISTED_QUERY_NOT_FOUND")) return false

            val searchRes = tryParseJson<SearchResponse>(searchResText)
            val edges = searchRes?.data?.shows?.edges ?: emptyList()
            if (edges.isEmpty()) return false

            val best = edges.firstOrNull { edge ->
                edge.englishName?.contains(title, ignoreCase = true) == true ||
                edge.name?.contains(title, ignoreCase = true) == true
            } ?: edges.first()

            val showId = best.id ?: return false

            val epNum = (episode ?: 1).toString()
            val dubStatus = "sub"
            val episodeVariables = """{"showId":"$showId","translationType":"$dubStatus","episodeString":"$epNum"}"""
            val episodeExtensions = """{"persistedQuery":{"version":1,"sha256Hash":"$SERVER_HASH"}}"""
            val episodeUrl = "$API_URL?variables=$episodeVariables&extensions=$episodeExtensions"

            val epText = app.get(episodeUrl, headers = DEFAULT_HEADERS).text
            if (epText.contains("PERSISTED_QUERY_NOT_FOUND")) return false

            val epRes = tryParseJson<EpisodeResponse>(epText)
            val sourceUrls = epRes?.data?.episode?.sourceUrls ?: emptyList()

            for (source in sourceUrls.take(8)) {
                try {
                    val rawLink = source.sourceUrl ?: continue
                    var link = rawLink

                    if (source.sourceName == "Ak" || rawLink.contains("/player/vitemb")) {
                        try {
                            val b64Payload = rawLink.substringAfter("=")
                            val decodedString = atob(b64Payload)
                            val decodedJson = tryParseJson<AkDecodedPayload>(decodedString)
                            link = decodedJson?.idUrl ?: rawLink
                        } catch (_: Exception) {
                            continue
                        }
                    } else {
                        link = rawLink.replace(" ", "%20")
                    }

                    if (link.startsWith("//")) {
                        link = "https:$link"
                    }

                    if (link.startsWith("--")) {
                        link = decryptHex(link)
                    }

                    if (link.startsWith("http")) {
                        loadExtractor(link, subtitleCallback, callback)
                        continue
                    }

                    val fixedLink = fixUrlPath(link)
                    val apiRes = app.get(fixedLink, headers = DEFAULT_HEADERS).parsedSafe<InternalLinksResponse>()

                    apiRes?.links?.forEach { server ->
                        if (server.hls != false && !server.link.isNullOrEmpty()) {
                            val sourceLabel = (source.sourceName ?: "SUB").uppercase()

                            callback(
                                ExtractorLink(
                                    source = "Anichi - $sourceLabel",
                                    name = "Anichi $sourceLabel",
                                    url = server.link,
                                    referer = "",
                                    quality = Qualities.P1080.value,
                                    type = if (server.link.contains(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                                )
                            )

                            server.subtitles?.forEach { sub ->
                                if (!sub.src.isNullOrEmpty()) {
                                    subtitleCallback(
                                        SubtitleFile(
                                            lang = sub.lang ?: "Unknown",
                                            url = sub.src
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Sample layout to extract provided data string formatted as "tmdbId,mediaType,season,episode"
        val parts = data.split(",")
        val tmdbId = parts.getOrNull(0) ?: return false
        val mediaType = parts.getOrNull(1) ?: "tv"
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        return getStreams(tmdbId, mediaType, season, episode, subtitleCallback, callback)
    }
}
