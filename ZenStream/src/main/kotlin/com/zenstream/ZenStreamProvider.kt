package com.zenstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.megix.CineStreamExtractors.invokeAllSources
import com.megix.CineStreamExtractors.invokeAllAnimeSources
import com.megix.AllLoadLinksData
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * ZenStream – Unified provider that merges Cinemeta (CineStream), Simkl (CineSimkl),
 * and TMDB (CineTmdb) into a single, deduplicated catalog and search.
 */
class ZenStreamProvider : MainAPI() {
    override var mainUrl = "https://cinemeta-catalogs.strem.io"
    override var name = "ZenStream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Torrent
    )

    // ---------- API endpoints ----------
    private val cinemeta = "https://v3-cinemeta.strem.io"
    private val kitsuUrl = "https://anime-kitsu.strem.fun"
    private val aiometa = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7"
    private val simklApi = "https://api.simkl.com"
    private val simklData = "https://data.simkl.in"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = BuildConfig.TMDB_KEY
    private val simklClientId = BuildConfig.SIMKL_CLIENT_ID
    private val imageProxy = "https://wsrv.nl/?url="

    // ---------- Main page categories (merged from all three) ----------
    override val mainPage = mainPageOf(
        // Cinemeta
        "$cinemeta/top/catalog/movie/top/skip=###" to "Top Movies",
        "$cinemeta/top/catalog/series/top/skip=###" to "Top Series",
        "$aiometa/catalog/anime/mal.airing/skip=###" to "Top Airing Anime",
        "$kitsuUrl/catalog/anime/kitsu-anime-trending/skip=###" to "Top Anime",
        // Simkl
        "/discover/trending/movies/today_500.json" to "Trending Movies Today",
        "/discover/trending/tv/today_500.json" to "Trending Shows Today",
        "/discover/trending/anime/today_500.json" to "Trending Anime Today",
        "/discover/trending/month_500.json" to "Trending This Month",
        // TMDB
        "trending/all/day?api_key=$tmdbKey&region=US" to "TMDB Trending",
        "trending/movie/week?api_key=$tmdbKey&region=US" to "TMDB Popular Movies",
        "trending/tv/week?api_key=$tmdbKey&region=US" to "TMDB Popular TV"
    )

    // ---------- Helpers ----------
    private fun getPoster(url: String?) = if (!url.isNullOrBlank()) imageProxy + url else null
    private fun getTmdbImage(path: String?) = if (!path.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$path" else null
    private fun getOriTmdbImage(path: String?) = if (!path.isNullOrBlank()) "https://image.tmdb.org/t/p/original$path" else null
    private fun getSimklPoster(id: String?) = if (!id.isNullOrBlank()) "${imageProxy}https://simkl.in/posters/${id}_m.webp" else null

    private fun getTvType(type: String?): TvType {
        return when (type) {
            "movie" -> TvType.Movie
            "anime" -> TvType.Anime
            "tv" -> TvType.TvSeries
            "show" -> TvType.TvSeries
            else -> TvType.TvSeries
        }
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when (status?.lowercase()) {
            "returning series", "ongoing" -> ShowStatus.Ongoing
            "ended" -> ShowStatus.Completed
            else -> null
        }
    }

    // ---------- Main Page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val results = mutableListOf<SearchResponse>()
        var hasNext = false

        when {
            url.contains("cinemeta") || url.contains("kitsu") || url.contains("aiometa") -> {
                val json = app.get("$url.json").text
                val home = tryParseJson<CinemetaHome>(json)
                home?.metas?.forEach { meta ->
                    val title = meta.aliases?.firstOrNull() ?: meta.name ?: return@forEach
                    val poster = if (meta.id.startsWith("tt")) imageProxy + "https://images.metahub.space/poster/medium/${meta.id}/img" else getPoster(meta.poster)
                    val resp = newMovieSearchResponse(title, PassData(meta.id, meta.type).toJson(), getTvType(meta.type)) {
                        this.posterUrl = poster
                        this.score = Score.from10(meta.imdbRating)
                    }
                    results.add(resp)
                }
                hasNext = home?.hasMore ?: false
            }
            url.contains(".json") -> {
                val json = app.get(simklData + url).text
                val data = parseJson<Array<SimklResponse>>(json)
                data.forEach {
                    val title = it.title ?: return@forEach
                    val resp = newMovieSearchResponse(title, "${simklApi}${it.url}", getTvType(it.type)) {
                        this.posterUrl = getSimklPoster(it.poster)
                        this.score = Score.from10(it.ratings?.imdb?.rating ?: it.ratings?.mal?.rating)
                    }
                    results.add(resp)
                }
                hasNext = true
            }
            url.contains("trending") || url.contains("discover") -> {
                val json = app.get("$tmdbApi/${url}&page=$page").text
                val data = tryParseJson<TmdbResults>(json)
                data?.results?.forEach { media ->
                    val title = media.title ?: media.name ?: return@forEach
                    val resp = newMovieSearchResponse(title, TmdbData(media.id, media.mediaType ?: "movie").toJson(), getTvType(media.mediaType)) {
                        this.posterUrl = getTmdbImage(media.posterPath)
                        this.score = Score.from10(media.voteAverage)
                    }
                    results.add(resp)
                }
                hasNext = true
            }
            else -> {
                val json = app.get("$simklApi$url&client_id=$simklClientId&page=$page").text
                val data = parseJson<Array<SimklResponse>>(json)
                data.forEach {
                    val title = it.title ?: return@forEach
                    val resp = newMovieSearchResponse(title, "$simklApi${it.url}", getTvType(it.type)) {
                        this.posterUrl = getSimklPoster(it.poster)
                        this.score = Score.from10(it.ratings?.imdb?.rating ?: it.ratings?.mal?.rating)
                    }
                    results.add(resp)
                }
                hasNext = true
            }
        }

        // Deduplicate by IMDB ID (or TMDB ID if IMDB missing)
        val unique = results.distinctBy { resp ->
            val url = resp.url
            when {
                url.contains("simkl.com") -> url.substringAfterLast("/").substringBefore("/")
                url.contains("tmdb.org") -> {
                    val data = tryParseJson<TmdbData>(resp.url)
                    data?.id
                }
                else -> {
                    val data = tryParseJson<PassData>(resp.url)
                    data?.id
                }
            } ?: resp.name
        }

        return newHomePageResponse(
            list = HomePageList(request.name, unique, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    // ---------- Quick Search & Full Search ----------
    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val cinemetaTask = async {
            runCatching {
                val json = app.get("$cinemeta/catalog/movie/top/search=$query.json").text
                tryParseJson<CinemetaSearch>(json)?.metas ?: emptyList()
            }.getOrDefault(emptyList())
        }
        val kitsuTask = async {
            runCatching {
                val json = app.get("$kitsuUrl/catalog/anime/kitsu-anime-airing/search=$query.json").text
                tryParseJson<CinemetaSearch>(json)?.metas ?: emptyList()
            }.getOrDefault(emptyList())
        }
        val simklTask = async {
            runCatching {
                val json = app.get("$simklApi/search/movie?q=$query&client_id=$simklClientId").text
                val movies = parseJson<Array<SimklResponse>>(json)
                movies.mapNotNull {
                    if (it.title == null) return@mapNotNull null
                    newMovieSearchResponse(it.title, "$simklApi${it.url}", getTvType(it.type)) {
                        this.posterUrl = getSimklPoster(it.poster)
                        this.score = Score.from10(it.ratings?.imdb?.rating ?: it.ratings?.mal?.rating)
                    }
                }
            }.getOrDefault(emptyList())
        }
        val tmdbTask = async {
            runCatching {
                val json = app.get("$tmdbApi/search/multi?api_key=$tmdbKey&query=$query").text
                val data = tryParseJson<TmdbResults>(json)
                data?.results?.mapNotNull { media ->
                    if (media.mediaType == "person") return@mapNotNull null
                    val title = media.title ?: media.name ?: return@mapNotNull null
                    newMovieSearchResponse(title, TmdbData(media.id, media.mediaType ?: "movie").toJson(), getTvType(media.mediaType)) {
                        this.posterUrl = getTmdbImage(media.posterPath)
                        this.score = Score.from10(media.voteAverage)
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

        val cinemetaMetas = cinemetaTask.await()
        val kitsuMetas = kitsuTask.await()
        val simklResults = simklTask.await()
        val tmdbResults = tmdbTask.await()

        val cinemetaResponses = cinemetaMetas.map {
            val title = it.aliases?.firstOrNull() ?: it.name ?: ""
            val poster = if (it.id.startsWith("tt")) imageProxy + "https://images.metahub.space/poster/medium/${it.id}/img" else getPoster(it.poster)
            newMovieSearchResponse(title, PassData(it.id, it.type).toJson(), getTvType(it.type)) {
                this.posterUrl = poster
                this.score = Score.from10(it.imdbRating)
            }
        }
        val kitsuResponses = kitsuMetas.map {
            val title = it.aliases?.firstOrNull() ?: it.name ?: ""
            newMovieSearchResponse(title, PassData(it.id, it.type).toJson(), TvType.Anime) {
                this.posterUrl = getPoster(it.poster)
                this.score = Score.from10(it.imdbRating)
            }
        }

        val all = cinemetaResponses + kitsuResponses + simklResults + tmdbResults

        // Deduplicate by IMDB/TMDB/AniList ID or title+year as fallback
        val unique = all.distinctBy { resp ->
            val url = resp.url
            when {
                url.contains("simkl.com") -> url.substringAfterLast("/").substringBefore("/")
                url.contains("tmdb.org") -> {
                    val data = tryParseJson<TmdbData>(url)
                    data?.id
                }
                else -> {
                    val data = tryParseJson<PassData>(url)
                    data?.id
                }
            } ?: "${resp.name}${resp.year}"
        }

        return@coroutineScope unique
    }

    // ---------- Load Details ----------
    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Any?>(url)

        return when {
            data is PassData -> loadFromCinemeta(data.id, data.type)
            url.contains("simkl.com") -> loadFromSimkl(url)
            url.contains("tmdb.org") -> {
                val d = parseJson<TmdbData>(url)
                loadFromTmdb(d.id, d.type ?: "movie")
            }
            else -> null
        }
    }

    // ---------- Load from Cinemeta (CineStream) ----------
    private suspend fun loadFromCinemeta(id: String, type: String): LoadResponse? {
        val tvType = getTvType(type)
        val metaUrl = if (id.contains("kitsu") || id.contains("mal")) "$kitsuUrl/meta/$type/${id.replace(":", "%3A")}.json" else "$cinemeta/meta/$type/$id.json"
        val json = app.get(metaUrl).text
        val meta = tryParseJson<CinemetaResponse>(json)?.meta ?: return null

        val imdbId = meta.imdb_id
        val tmdbId = meta.moviedb_id
        val anilistId = if (id.contains("kitsu") || id.contains("mal")) getExternalId(id, "kitsu")?.anilist else null
        val malId = if (id.contains("kitsu") || id.contains("mal")) getExternalId(id, "kitsu")?.myanimelist else null
        val kitsuId = if (id.contains("kitsu") || id.contains("mal")) getExternalId(id, "kitsu")?.kitsu else null

        val title = meta.name ?: "Unknown"
        val poster = getPoster(meta.poster)
        val bg = getPoster(meta.background)
        val logo = getPoster(meta.logo)
        val description = meta.description
        val genres = meta.genre ?: meta.genres ?: emptyList()
        val imdbRating = meta.imdbRating?.toDoubleOrNull()
        val year = meta.year?.substringBefore("-")?.toIntOrNull() ?: meta.releaseInfo?.toIntOrNull()

        val isAnime = meta.country?.contains("Japan") == true && genres.contains("Animation")
        val isBollywood = meta.country?.contains("India") == true
        val isAsian = !isAnime && (meta.country?.contains("Korea") == true || meta.country?.contains("China") == true)
        val isCartoon = genres.contains("Animation")

        if (tvType == TvType.Movie) {
            val loadData = AllLoadLinksData(
                title = title,
                imdbId = imdbId,
                tmdbId = tmdbId,
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
                year = year,
                airedYear = year,
                season = null,
                episode = null,
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                originalTitle = null,
                imdbTitle = null,
                imdbSeason = null,
                imdbEpisode = null,
                imdbYear = null
            ).toJson()

            return newMovieLoadResponse(title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, loadData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = description
                this.tags = genres
                this.score = Score.from10(imdbRating)
                this.year = year
                this.logoUrl = logo
                addImdbId(imdbId)
                addAniListId(anilistId)
                addMalId(malId)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            meta.videos?.forEach { ep ->
                if (ep.season == 0) return@forEach
                val epData = AllLoadLinksData(
                    title = title,
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    anilistId = anilistId,
                    malId = malId,
                    kitsuId = kitsuId,
                    year = year,
                    airedYear = ep.firstAired?.substringBefore("-")?.toIntOrNull() ?: ep.released?.substringBefore("-")?.toIntOrNull(),
                    season = ep.season,
                    episode = ep.episode,
                    isAnime = isAnime,
                    isBollywood = isBollywood,
                    isAsian = isAsian,
                    isCartoon = isCartoon,
                    originalTitle = null,
                    imdbTitle = null,
                    imdbSeason = ep.imdbSeason,
                    imdbEpisode = ep.imdbEpisode,
                    imdbYear = null
                ).toJson()
                episodes.add(
                    newEpisode(epData) {
                        this.name = ep.name ?: ep.title ?: "Episode ${ep.episode}"
                        this.season = ep.season
                        this.episode = ep.episode
                        this.posterUrl = getPoster(ep.thumbnail)
                        this.description = ep.overview
                        this.score = Score.from10(ep.rating?.toDoubleOrNull())
                        addDate(ep.firstAired ?: ep.released)
                    }
                )
            }

            return newAnimeLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = year
                this.plot = description
                this.tags = genres
                this.logoUrl = logo
                this.score = Score.from10(imdbRating)
                this.showStatus = getStatus(meta.status)
                addImdbId(imdbId)
                addAniListId(anilistId)
                addMalId(malId)
            }
        }
    }

    // ---------- Load from Simkl ----------
    private suspend fun loadFromSimkl(url: String): LoadResponse? {
        val (id, type) = getSimklIdAndType(url)
        val json = app.get("$simklApi/$type/$id?client_id=${BuildConfig.SIMKL_API}&extended=full").text
        val meta = tryParseJson<SimklResponse>(json) ?: return null

        val imdbId = meta.ids?.imdb
        val tmdbId = meta.ids?.tmdb?.toIntOrNull()
        val anilistId = meta.ids?.anilist?.toIntOrNull()
        val malId = meta.ids?.mal?.toIntOrNull()
        val kitsuId = meta.ids?.kitsu

        val title = meta.title ?: "Unknown"
        val poster = getSimklPoster(meta.poster)
        val bg = getPoster(meta.fanart)
        val description = meta.overview
        val genres = meta.genres ?: emptyList()
        val rating = meta.ratings?.imdb?.rating ?: meta.ratings?.mal?.rating
        val year = meta.year
        val status = meta.status
        val tvType = getTvType(meta.type)

        val isAnime = tvType == TvType.Anime
        val isBollywood = meta.country == "IN"
        val isAsian = !isAnime && (meta.country == "KR" || meta.country == "CN")
        val isCartoon = genres.contains("Animation")

        if (tvType == TvType.Movie) {
            val loadData = AllLoadLinksData(
                title = title,
                imdbId = imdbId,
                tmdbId = tmdbId,
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
                year = year,
                airedYear = year,
                season = null,
                episode = null,
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                originalTitle = null,
                imdbTitle = null,
                imdbSeason = null,
                imdbEpisode = null,
                imdbYear = null
            ).toJson()

            return newMovieLoadResponse(title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, loadData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = description
                this.tags = genres
                this.score = Score.from10(rating)
                this.year = year
                addImdbId(imdbId)
                addAniListId(anilistId)
                addMalId(malId)
            }
        } else {
            val epsJson = app.get("$simklApi/tv/episodes/$id?client_id=${BuildConfig.SIMKL_API}&extended=full").text
            val eps = parseJson<Array<SimklEpisode>>(epsJson)
            val episodes = eps.map {
                val epData = AllLoadLinksData(
                    title = title,
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    anilistId = anilistId,
                    malId = malId,
                    kitsuId = kitsuId,
                    year = year,
                    airedYear = it.date?.substringBefore("-")?.toIntOrNull(),
                    season = it.season,
                    episode = it.episode,
                    isAnime = isAnime,
                    isBollywood = isBollywood,
                    isAsian = isAsian,
                    isCartoon = isCartoon,
                    originalTitle = null,
                    imdbTitle = null,
                    imdbSeason = it.tvdb?.season,
                    imdbEpisode = it.tvdb?.episode,
                    imdbYear = null
                ).toJson()
                newEpisode(epData) {
                    this.name = it.title ?: "Episode ${it.episode}"
                    this.season = it.season
                    this.episode = it.episode
                    this.description = it.description
                    this.posterUrl = getSimklPoster(it.img)
                    addDate(it.date)
                }
            }

            return newAnimeLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = year
                this.plot = description
                this.tags = genres
                this.score = Score.from10(rating)
                this.showStatus = getStatus(status)
                addImdbId(imdbId)
                addAniListId(anilistId)
                addMalId(malId)
            }
        }
    }

    // ---------- Load from TMDB ----------
    private suspend fun loadFromTmdb(id: Int, type: String): LoadResponse? {
        val append = "alternative_titles,credits,external_ids,videos,recommendations,content_ratings,release_dates"
        val url = if (type == "movie") "$tmdbApi/movie/$id?api_key=$tmdbKey&append_to_response=$append" else "$tmdbApi/tv/$id?api_key=$tmdbKey&append_to_response=$append"
        val json = app.get(url).text
        val meta = tryParseJson<TmdbDetail>(json) ?: return null

        val imdbId = meta.external_ids?.imdb_id
        val tmdbId = meta.id
        val title = meta.title ?: meta.name ?: "Unknown"
        val poster = getTmdbImage(meta.posterPath)
        val bg = getTmdbImage(meta.backdropPath)
        val description = meta.overview
        val genres = meta.genres?.map { it.name } ?: emptyList()
        val rating = meta.vote_average?.toDoubleOrNull()
        val year = meta.releaseDate?.substringBefore("-")?.toIntOrNull() ?: meta.firstAirDate?.substringBefore("-")?.toIntOrNull()
        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
        val isAnime = genres.contains("Animation") && (meta.original_language == "ja" || meta.original_language == "zh" || meta.original_language == "ko")
        val isBollywood = meta.production_countries?.any { it.name == "India" } ?: false
        val isAsian = !isAnime && (meta.original_language == "ko" || meta.original_language == "zh")
        val isCartoon = genres.contains("Animation")

        if (tvType == TvType.Movie) {
            val loadData = AllLoadLinksData(
                title = title,
                imdbId = imdbId,
                tmdbId = tmdbId,
                anilistId = null,
                malId = null,
                kitsuId = null,
                year = year,
                airedYear = year,
                season = null,
                episode = null,
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                originalTitle = null,
                imdbTitle = null,
                imdbSeason = null,
                imdbEpisode = null,
                imdbYear = null
            ).toJson()

            return newMovieLoadResponse(title, url, if (isAnime) TvType.AnimeMovie else TvType.Movie, loadData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = description
                this.tags = genres
                this.score = Score.from10(rating)
                this.year = year
                addImdbId(imdbId)
            }
        } else {
            val seasons = meta.seasons?.filter { it.seasonNumber != 0 } ?: emptyList()
            val episodes = mutableListOf<Episode>()
            seasons.forEach { season ->
                val seasonJson = app.get("$tmdbApi/tv/$id/season/${season.seasonNumber}?api_key=$tmdbKey").text
                val seasonData = tryParseJson<TmdbSeason>(seasonJson)
                seasonData?.episodes?.forEach { ep ->
                    val epData = AllLoadLinksData(
                        title = title,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                        anilistId = null,
                        malId = null,
                        kitsuId = null,
                        year = year,
                        airedYear = ep.airDate?.substringBefore("-")?.toIntOrNull(),
                        season = ep.seasonNumber,
                        episode = ep.episodeNumber,
                        isAnime = isAnime,
                        isBollywood = isBollywood,
                        isAsian = isAsian,
                        isCartoon = isCartoon,
                        originalTitle = null,
                        imdbTitle = null,
                        imdbSeason = null,
                        imdbEpisode = null,
                        imdbYear = null
                    ).toJson()
                    episodes.add(
                        newEpisode(epData) {
                            this.name = ep.name ?: "Episode ${ep.episodeNumber}"
                            this.season = ep.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = getTmdbImage(ep.stillPath)
                            this.description = ep.overview
                            this.score = Score.from10(ep.voteAverage)
                            addDate(ep.airDate)
                        }
                    )
                }
            }

            return newAnimeLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = year
                this.plot = description
                this.tags = genres
                this.score = Score.from10(rating)
                this.showStatus = getStatus(meta.status)
                addImdbId(imdbId)
            }
        }
    }

    // ---------- Load Links (reuses existing extractors) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<AllLoadLinksData>(data)
        if (res.isAnime) {
            invokeAllAnimeSources(res, subtitleCallback, callback)
        } else {
            invokeAllSources(res, subtitleCallback, callback)
        }
        return true
    }

    // ---------- Utility data classes ----------
    data class PassData(val id: String, val type: String)
    data class TmdbData(val id: Int, val type: String? = null)

    // ---------- Simkl ID extraction ----------
    private fun getSimklIdAndType(url: String): Pair<String, String> {
        val id = url.split('/').find { it.toIntOrNull() != null } ?: ""
        val type = when {
            url.contains("/movies/") -> "movie"
            url.contains("/anime/") -> "anime"
            else -> "tv"
        }
        return id to type
    }

    // ---------- External ID resolver (for Kitsu/AniList) ----------
    private suspend fun getExternalId(id: String, source: String): ExternalIds? {
        val url = "https://arm.haglund.dev/api/v2/ids?source=$source&id=$id"
        val json = app.get(url).text
        return tryParseJson<ExternalIds>(json)
    }

    // ---------- Data classes for parsing ----------
    data class CinemetaHome(val metas: List<CinemetaMeta>, val hasMore: Boolean = true)
    data class CinemetaSearch(val metas: List<CinemetaMeta>)
    data class CinemetaResponse(val meta: CinemetaMeta)
    data class CinemetaMeta(
        val id: String,
        val imdb_id: String?,
        val type: String,
        val name: String?,
        val aliases: List<String>?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val videos: List<CinemetaVideo>?
    )
    data class CinemetaVideo(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val rating: String?,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?
    )

    data class SimklResponse(
        val title: String?,
        val en_title: String?,
        val type: String?,
        val url: String?,
        val poster: String?,
        val fanart: String?,
        val ids: SimklIds?,
        val ratings: SimklRatings?,
        val country: String?,
        val status: String?,
        val overview: String?,
        val year: Int?,
        val genres: List<String>?
    )
    data class SimklIds(val imdb: String?, val tmdb: String?, val mal: String?, val anilist: String?, val kitsu: String?)
    data class SimklRatings(val imdb: SimklRating?, val mal: SimklRating?)
    data class SimklRating(val rating: Double?)
    data class SimklEpisode(
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val description: String?,
        val img: String?,
        val date: String?,
        val tvdb: SimklTvdb?
    )
    data class SimklTvdb(val season: Int?, val episode: Int?)

    data class TmdbResults(val results: List<TmdbMedia>)
    data class TmdbMedia(
        val id: Int,
        val mediaType: String?,
        val title: String?,
        val name: String?,
        val posterPath: String?,
        val voteAverage: Double?
    )
    data class TmdbDetail(
        val id: Int,
        val title: String?,
        val name: String?,
        val posterPath: String?,
        val backdropPath: String?,
        val overview: String?,
        val genres: List<TmdbGenre>?,
        val vote_average: Any?,
        val releaseDate: String?,
        val firstAirDate: String?,
        val original_language: String?,
        val status: String?,
        val seasons: List<TmdbSeasonSummary>?,
        val external_ids: TmdbExternalIds?,
        val production_countries: List<TmdbCountry>?
    )
    data class TmdbGenre(val name: String?)
    data class TmdbSeasonSummary(val seasonNumber: Int)
    data class TmdbExternalIds(val imdb_id: String?)
    data class TmdbCountry(val name: String?)
    data class TmdbSeason(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(
        val name: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val airDate: String?,
        val stillPath: String?,
        val overview: String?,
        val voteAverage: Double?
    )

    data class ExternalIds(val anilist: Int?, val myanimelist: Int?, val kitsu: Int?)
}
