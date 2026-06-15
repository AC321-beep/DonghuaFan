package com.livesports

import com.fasterxml.jackson.annotation.JsonProperty

data class LiveEventData(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("title") val title: String = "",
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("cat") val cat: String? = null,
    @JsonProperty("eventInfo") val eventInfo: LiveEventInfo? = null,
    @JsonProperty("publish") val publish: Int = 0,
    @JsonProperty("formats") val formats: List<LiveEventFormat>? = null
)

data class LiveEventInfo(
    @JsonProperty("teamA") val teamA: String? = null,
    @JsonProperty("teamB") val teamB: String? = null,
    @JsonProperty("teamAFlag") val teamAFlag: String? = null,
    @JsonProperty("teamBFlag") val teamBFlag: String? = null,
    @JsonProperty("eventCat") val eventCat: String? = null,
    @JsonProperty("eventName") val eventName: String? = null,
    @JsonProperty("eventLogo") val eventLogo: String? = null,
    @JsonProperty("isHot") val isHot: String? = null,
    @JsonProperty("eventType") val eventType: String? = null,
    @JsonProperty("startTime") val startTime: String? = null,
    @JsonProperty("endTime") val endTime: String? = null
)

data class LiveEventFormat(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("webLink") val webLink: String? = null
)
