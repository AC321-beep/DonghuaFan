package com.livesports

import com.fasterxml.jackson.annotation.JsonProperty

data class FirebaseConfig(
    @JsonProperty("api_url") val apiUrl: String,
    @JsonProperty("encryption_key") val encryptionKey: String,
    @JsonProperty("encryption_iv") val encryptionIv: String
)

data class LiveEventResponse(
    @JsonProperty("events") val events: List<LiveEvent>
)

data class LiveEvent(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("category") val category: String,
    @JsonProperty("thumbnail") val thumbnail: String?,
    @JsonProperty("streams") val streams: List<LiveStreamConfig>
)

data class LiveStreamConfig(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("is_drm") val isDrm: Boolean = false,
    @JsonProperty("clear_key_id") val clearKeyId: String? = null,
    @JsonProperty("clear_key_value") val clearKeyValue: String? = null,
    @JsonProperty("use_webview") val useWebView: Boolean = false,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)
