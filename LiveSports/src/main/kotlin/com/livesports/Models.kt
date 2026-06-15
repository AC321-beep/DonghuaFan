package com.livesports

data class LiveEventData(
    val id: Int, val title: String, val slug: String, val cat: String?,
    val formats: List<LiveEventFormat>?, val eventInfo: LiveEventInfo?
)
data class LiveEventFormat(val name: String?, val link: String?, val api: String?, val type: String?)
data class LiveEventInfo(
    val teamA: String?, val teamB: String?, val teamAFlag: String?, val teamBFlag: String?,
    val eventName: String?, val eventType: String?, val eventCat: String?, val eventLogo: String?,
    val startTime: String?, val endTime: String?
)
data class ChannelStreamResponse(
    val streamUrls: List<StreamUrl>?, val related: List<LiveEventData>?,
    val prevChannel: String?, val nextChannel: String?
)
data class StreamUrl(
    val api: String?, val id: Int?, val link: String?, val title: String?,
    val type: String?, val webLink: String?
)
data class LiveEventLoadData(
    val eventId: Int, val title: String, val poster: String, val slug: String,
    val formats: List<LiveEventFormat>, val eventInfo: LiveEventInfo?
)
