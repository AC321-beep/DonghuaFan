package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.mangamanhwaverse.providers.*

object AppContext {
    var ctx: Context? = null
}

object ProviderRegistry {
    val providers = listOf(
        // ThemeSites (Madara, Toonily, MangaBox)
        Manhuanext(), Toonily(),
        MangaKakalot(), MangaBat(),

        // NextGen Web / API Providers
        FlameComics(), AsuraScans(), HiveToon(), NyxScans(),

        // Dedicated Custom / Aggregator Providers
        MangaDex(),
        OmegaScans(),
        ComixProvider()
    )

    val names: List<String> get() = providers.map { it.name }

    /**
     * Dynamically syncs enabled/disabled providers into Cloudstream's in-memory APIHolder
     * so changes apply immediately without killing or restarting the app.
     */
    fun syncActiveProviders() {
        runCatching {
            val ourNames = names.toSet()
            val desiredProviders = providers.filter { Settings.isProviderEnabled(it.name) }

            synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.removeAll { it.name in ourNames }
                APIHolder.allProviders.addAll(desiredProviders)
            }

            // Also update APIHolder.apis if this Cloudstream build exposes it as a MutableList
            runCatching {
                val apisField = APIHolder::class.java.getDeclaredField("apis").apply { isAccessible = true }
                val currentApis = apisField.get(APIHolder)
                if (currentApis is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val mutableApis = currentApis as MutableList<MainAPI>
                    synchronized(mutableApis) {
                        mutableApis.removeAll { it.name in ourNames }
                        mutableApis.addAll(desiredProviders)
                    }
                } else if (currentApis is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val updated = (currentApis as List<MainAPI>).filter { it.name !in ourNames } + desiredProviders
                    apisField.set(APIHolder, updated)
                }
            }
        }
    }
}

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        AppContext.ctx = context
        Settings.init(context)

        if (!Settings.hasInitializedProviderSet()) {
            Settings.setEnabledProviderNames(
                if (Settings.allProvidersEnabled()) ProviderRegistry.names.toSet()
                else emptySet()
            )
        }

        ProviderRegistry.providers
            .filter { Settings.isProviderEnabled(it.name) }
            .forEach { registerMainAPI(it) }

        this.openSettings = { ctx -> Settings.show(ctx) }
    }
}
