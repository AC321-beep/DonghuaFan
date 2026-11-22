package com.Animexin

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimexinPlugin : Plugin() {
    override fun load(context: Context) {
        AnimexinLogger.init(context)

        // Register the main scraper
        registerMainAPI(AnimexinProvider())

        // Register all custom extractors
        registerExtractorAPI(Rumble())
        registerExtractorAPI(Odysee())
        registerExtractorAPI(MegaNz())
        registerExtractorAPI(OkRu())
        registerExtractorAPI(Dtube())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(Waaw())
        registerExtractorAPI(Wishfast())
        registerExtractorAPI(FileMoonSx())

        // In-app settings dialog (accessible via the extension gear icon in CloudStream)
        openSettings = { ctx: Context ->
            val padding = (20 * ctx.resources.displayMetrics.density).toInt()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

            val switchView = Switch(ctx).apply {
                text = "Enable Verbose Logcat Logging"
                isChecked = AnimexinLogger.isVerbose
                setOnCheckedChangeListener { _, isChecked ->
                    AnimexinLogger.setVerbose(ctx, isChecked)
                }
            }

            val hintView = TextView(ctx).apply {
                text = "Logs extraction steps under tag 'AnimeXin/' in logcat. Keep off during normal playback."
                textSize = 12f
                setPadding(0, padding / 2, 0, 0)
            }

            container.addView(switchView)
            container.addView(hintView)

            AlertDialog.Builder(ctx)
                .setTitle("AnimeXin Settings")
                .setView(container)
                .setPositiveButton("Done", null)
                .show()
        }
    }
}
