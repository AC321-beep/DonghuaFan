package com.zenstream

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class ZenStreamPlugin : Plugin() {

    override fun load(context: Context) {
        // Register the unified provider if enabled
        if (getKey<Boolean>(ZenStreamSettings.PROVIDER_ENABLED) ?: true) {
            registerMainAPI(ZenStreamProvider())
        }

        // (Optional) If you want this plugin to work standalone without the original CineStream,
        // you need to re‑register all extractors here. Otherwise, they must be loaded by another plugin.
        // For simplicity, we assume the extractors are already loaded elsewhere (e.g., by the original CineStream).
        // If you want this plugin to be fully independent, uncomment the following block and add all extractor classes.
        /*
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Pahe())
        registerExtractorAPI(SuperVideo())
        // ... all other extractors ...
        */

        // Settings entry
        this.openSettings = { ctx: Context ->
            ZenStreamSettingsDialog.show(ctx) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
