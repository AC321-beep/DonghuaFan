package com.zenstream

data class AllLoadLinksData(
    val title: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val year: Int? = null,
    val airedYear: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isAnime: Boolean = false,
    val isBollywood: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false,
    val originalTitle: String? = null,
    val imdbTitle: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
    val imdbYear: Int? = null
)
