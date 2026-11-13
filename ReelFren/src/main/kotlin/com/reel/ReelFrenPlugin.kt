package com.reel

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCloudflare.init(context)
        Reelfren.setContext(context)

        registerMainAPI(Reelfren())
        registerExtractorAPI(ReelFrenDirectLinkExtractor())

        openSettings = {
            val activity = CommonActivity.activity
            
            // FIX: Replaced the early return with a null check
            if (activity != null) {
                
                // The default 10 providers that will be pre-checked
                val defaultEnabledSlugs = setOf(
                    "dramabox", "reelshort", "wetv", "happyshort", "shorten",
                    "dramawave", "shortmax", "netshort", "pinedrama", "vigloo"
                )

                val providersMap = linkedMapOf(
                    "Anamana"         to "anamana",
                    "AniBoy"          to "aniboy",
                    "Melolo"          to "melolo",
                    "Sereal+"         to "sereal",
                    "PineDrama"       to "pinedrama",
                    "Shorten"         to "shorten",
                    "HappyShort"      to "happyshort",
                    "Vigloo"          to "vigloo",
                    "Vigloo 2"        to "vigloo2",
                    "RapidTV"         to "rapidtv",
                    "RapidTV 2"       to "rapidtv2",
                    "RaptDrama"       to "raptdrama",
                    "CubeTV"          to "cubetv",
                    "JoyReels"        to "joyreels",
                    "AnyReel"         to "anyreel",
                    "Shortical"       to "shortical",
                    "StoReel"         to "storeel",
                    "LoveShots"       to "loveshots",
                    "MomeShort"       to "momeshort",
                    "MoreShort"       to "moreshort",
                    "ToonShort"       to "toonshort",
                    "SixthShort"      to "sixthshort",
                    "SoReel"          to "soreel",
                    "StoryGo"         to "storygo",
                    "MiniShort"       to "minishort",
                    "WinkNovel"       to "winknovel",
                    "My Muse"         to "mymuse",
                    "SarosTV"         to "sarostv",
                    "ReelAI"          to "reelai",
                    "Reelife"         to "reelife",
                    "ReelShort"       to "reelshort",
                    "ReelShort 2"     to "reelshort2",
                    "DramaBox"        to "dramabox",
                    "DramaWave"       to "dramawave",
                    "DramaNova"       to "dramanova",
                    "KalosTV"         to "kalostv",
                    "VibeShort"       to "vibeshort",
                    "FreeReels"       to "freereels",
                    "WeTV"            to "wetv",
                    "StoryReel"       to "storyreel",
                    "MovieBox Shorts" to "movieboxshorts",
                    "BonusTV"         to "bonustv",
                    "MoboReels"       to "moboreels",
                    "NetShort"        to "netshort",
                    "MyDrama"         to "mydrama",
                    "FlareFlow"       to "flareflow",
                    "PlayLet"         to "playlet",
                    "ShortMax"        to "shortmax",
                    "FlexTV"          to "flextv",
                    "FlexTV 2"        to "flextv2",
                    "CandyJar"        to "candyjar",
                    "BlinkDrama"      to "blinkdrama",
                    "iQIYI"           to "iqiyi",
                    "MovieBox"        to "moviebox",
                    "FilmBox"         to "filmbox"
                )

                val providerNames = providersMap.keys.toTypedArray()
                val providerSlugs = providersMap.values.toTypedArray()
                
                val prefs = context.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)
                
                val checkedStates = BooleanArray(providerNames.size) { index ->
                    val slug = providerSlugs[index]
                    prefs.getBoolean("provider_$slug", defaultEnabledSlugs.contains(slug))
                }

                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Select Providers")
                        .setMultiChoiceItems(providerNames, checkedStates, DialogInterface.OnMultiChoiceClickListener { _, which, isChecked ->
                            checkedStates[which] = isChecked
                        })
                        .setPositiveButton("Save", DialogInterface.OnClickListener { _, _ ->
                            val editor = prefs.edit()
                            for (i in providerNames.indices) {
                                editor.putBoolean("provider_${providerSlugs[i]}", checkedStates[i])
                            }
                            editor.apply()
                            Toast.makeText(activity, "Providers updated! Restart extension to apply.", Toast.LENGTH_LONG).show()
                        })
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                Toast.makeText(context, "Cannot open settings right now", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
