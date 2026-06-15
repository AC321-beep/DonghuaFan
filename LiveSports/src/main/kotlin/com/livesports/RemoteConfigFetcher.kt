package com.livesports

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object RemoteConfigFetcher {
    private const val PACKAGE_NAME = "com.livesports"
    
    // Fallback – will be overridden by BuildConfig if available
    private val API_KEY: String by lazy {
        try { BuildConfig.LIVESPORTS_FIREBASE_API_KEY } catch (e: Exception) { "" }
    }
    private val APP_ID: String by lazy {
        try { BuildConfig.LIVESPORTS_FIREBASE_APP_ID } catch (e: Exception) { "" }
    }
    private val PROJECT_NUMBER: String by lazy {
        try { BuildConfig.LIVESPORTS_FIREBASE_PROJECT_NUMBER } catch (e: Exception) { "" }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(val entries: Map<String, String>? = null)

    suspend fun fetchRemoteConfig(): Map<String, String>? {
        if (API_KEY.isBlank() || APP_ID.isBlank() || PROJECT_NUMBER.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"
                val payload = """
                    {
                        "appInstanceId": "${UUID.randomUUID().toString().replace("-", "")}",
                        "appId": "$APP_ID",
                        "packageName": "$PACKAGE_NAME",
                        "appVersion": "5.0",
                        "sdkVersion": "22.1.0"
                    }
                """.trimIndent()
                val request = Request.Builder().url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("X-Goog-Api-Key", API_KEY)
                    .header("X-Android-Package", PACKAGE_NAME)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val resp = parseJson<RemoteConfigResponse>(response.body.string())
                    return@withContext resp.entries
                }
            } catch (e: Exception) { e.printStackTrace() }
            null
        }
    }

    suspend fun getProviderApiUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("cric_api2") ?: entries?.get("cric_api1")
    }
}
