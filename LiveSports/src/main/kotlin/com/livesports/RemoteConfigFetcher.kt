package com.livesports

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.UUID

object RemoteConfigFetcher {
    // This MUST match the package name registered in the Firebase console
    private const val PACKAGE_NAME = "com.cricfy.tv"
    
    // Pulls the keys injected by GitHub Actions Secrets during the build
    private fun getApiKey() = try { BuildConfig.LIVESPORTS_FIREBASE_API_KEY } catch (e: Exception) { "" }
    private fun getAppId() = try { BuildConfig.LIVESPORTS_FIREBASE_APP_ID } catch (e: Exception) { "" }
    private fun getProjectNumber() = try { BuildConfig.LIVESPORTS_FIREBASE_PROJECT_NUMBER } catch (e: Exception) { "" }

    data class RemoteConfigResponse(val entries: Map<String, String>? = null)

    suspend fun fetchRemoteConfig(): Map<String, String>? {
        val apiKey = getApiKey()
        val appId = getAppId()
        val projNum = getProjectNumber()
        
        if (apiKey.isBlank() || appId.isBlank() || projNum.isBlank()) return null
        
        return try {
            val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$projNum/namespaces/firebase:fetch"
            
            // Map the JSON payload cleanly
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
            
            // Cloudstream's native app.post() runs asynchronously automatically (No OkHttp/Coroutines needed!)
            val responseText = app.post(url, headers = headers, json = payload).text
            
            if (responseText.isNotBlank()) {
                val resp = parseJson<RemoteConfigResponse>(responseText)
                resp.entries
            } else {
                null
            }
        } catch (e: Exception) { 
            e.printStackTrace()
            null
        }
    }

    suspend fun getProviderApiUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("cric_api2") ?: entries?.get("cric_api1")
    }
}
