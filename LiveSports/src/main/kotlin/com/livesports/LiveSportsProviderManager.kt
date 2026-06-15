package com.livesports

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.UUID

object LiveSportsProviderManager {
    private const val FALLBACK_BASE_URL = "https://cfyhljddgbkkufh82.top"
    private const val PACKAGE_NAME = "com.cricfy.tv"
    private var cachedBaseUrl: String? = null

    data class RemoteConfigResponse(val entries: Map<String, String>? = null)

    suspend fun getBaseUrl(): String {
        if (cachedBaseUrl != null) return cachedBaseUrl!!
        
        val apiKey = try { BuildConfig.LIVESPORTS_FIREBASE_API_KEY } catch (e: Exception) { "" }
        val appId = try { BuildConfig.LIVESPORTS_FIREBASE_APP_ID } catch (e: Exception) { "" }
        val projNum = try { BuildConfig.LIVESPORTS_FIREBASE_PROJECT_NUMBER } catch (e: Exception) { "" }

        if (apiKey.isNotBlank() && appId.isNotBlank() && projNum.isNotBlank()) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$projNum/namespaces/firebase:fetch"
                val payload = mapOf(
                    "appInstanceId" to UUID.randomUUID().toString().replace("-", ""),
                    "appId" to appId,
                    "packageName" to PACKAGE_NAME,
                    "appVersion" to "5.0",
                    "sdkVersion" to "22.1.0"
                )
                val headers = mapOf(
                    "X-Goog-Api-Key" to apiKey,
                    "X-Android-Package" to PACKAGE_NAME,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                )
                // Replaced OkHttp with native app.post()
                val responseText = app.post(url, headers = headers, json = payload).text
                if (responseText.isNotBlank()) {
                    val resp = parseJson<RemoteConfigResponse>(responseText)
                    cachedBaseUrl = resp.entries?.get("cric_api2") ?: resp.entries?.get("cric_api1")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        
        if (cachedBaseUrl == null) cachedBaseUrl = FALLBACK_BASE_URL
        return cachedBaseUrl!!
    }

    suspend fun fetchProviders(): List<Map<String, Any>> {
        try {
            val response = app.get("${getBaseUrl()}/cats.txt", headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            val decrypted = CryptoUtils.decryptData(response)
            if (!decrypted.isNullOrBlank()) return parseJson(decrypted)
        } catch (e: Exception) { e.printStackTrace() }
        return emptyList()
    }

    suspend fun fetchLiveEvents(): List<LiveEventData> {
        try {
            val response = app.get("${getBaseUrl()}/live-events.txt", headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            val decrypted = CryptoUtils.decryptData(response)
            if (!decrypted.isNullOrBlank()) return parseJson(decrypted)
        } catch (e: Exception) { e.printStackTrace() }
        return emptyList()
    }
}
