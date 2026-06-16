package com.livesports

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

object LiveSportsProviderManager {
    // New updated fallback domain from CNC smali
    private const val FALLBACK_BASE_URL = "https://cfymarkscanjiostar80.top"
    private const val PACKAGE_NAME = "com.cricfy.tv"
    private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(val entries: Map<String, String>? = null)

    suspend fun getBaseUrl(): String {
        if (cachedBaseUrl != null) return cachedBaseUrl!!
        
        val apiKey = try { BuildConfig.LIVESPORTS_FIREBASE_API_KEY } catch (e: Exception) { "" }
        val appId = try { BuildConfig.LIVESPORTS_FIREBASE_APP_ID } catch (e: Exception) { "" }
        val projNum = try { BuildConfig.LIVESPORTS_FIREBASE_PROJECT_NUMBER } catch (e: Exception) { "" }

        if (apiKey.isNotBlank() && appId.isNotBlank() && projNum.isNotBlank()) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$projNum/namespaces/firebase:fetch"
                val payload = """
                    {
                        "appInstanceId": "${UUID.randomUUID().toString().replace("-", "")}",
                        "appId": "$appId",
                        "packageName": "$PACKAGE_NAME",
                        "appVersion": "5.0",
                        "sdkVersion": "22.1.0"
                    }
                """.trimIndent()

                val request = Request.Builder().url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Android-Package", PACKAGE_NAME)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val resp = parseJson<RemoteConfigResponse>(response.body?.string() ?: "")
                    cachedBaseUrl = resp.entries?.get("cric_api2") ?: resp.entries?.get("cric_api1")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        
        if (cachedBaseUrl == null) cachedBaseUrl = FALLBACK_BASE_URL
        return cachedBaseUrl!!
    }

    suspend fun fetchLiveEvents(): List<LiveEventData> {
        try {
            val url = "${getBaseUrl()}/live-events.txt"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val decrypted = CryptoUtils.decryptData(response.body?.string() ?: "")
                if (!decrypted.isNullOrBlank()) return parseJson(decrypted)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return emptyList()
    }
}
