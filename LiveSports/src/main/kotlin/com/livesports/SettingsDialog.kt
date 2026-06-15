package com.livesports

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class SettingsDialog(
    private val plugin: LiveSportsPlugin,
    private val prefs: SharedPreferences?,
    private val playlistNames: List<String>
) : BottomSheetDialogFragment() {

    private val enabled = playlistNames.filter { prefs?.getBoolean(it, false) == true }.toMutableSet()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        TextView(requireContext()).apply {
            text = "Select IPTV Playlists"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            root.addView(this)
        }

        val scroll = ScrollView(requireContext())
        val listLayout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        playlistNames.forEach { name ->
            val cb = CheckBox(requireContext()).apply {
                text = name.substringAfter("playlist_")
                isChecked = enabled.contains(name)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) enabled.add(name) else enabled.remove(name)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            listLayout.addView(cb)
        }
        scroll.addView(listLayout)
        root.addView(scroll)

        Button(requireContext()).apply {
            text = "Save"
            setOnClickListener {
                prefs?.edit()?.clear()?.apply()
                enabled.forEach { prefs?.edit()?.putBoolean(it, true)?.apply() }
                AlertDialog.Builder(requireContext())
                    .setTitle("Restart Required")
                    .setMessage("Changes saved. Restart app to apply?")
                    .setPositiveButton("Yes") { _, _ ->
                        dismiss()
                        restartApp()
                    }
                    .setNegativeButton("No") { _, _ ->
                        dismiss()
                        showToast("Settings saved. Restart app later.")
                    }
                    .show()
            }
            root.addView(this)
        }
        return root
    }

    private fun restartApp() {
        val ctx = requireContext().applicationContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        intent?.component?.let {
            ctx.startActivity(android.content.Intent.makeRestartActivityTask(it))
            Runtime.getRuntime().exit(0)
        }
    }
}
