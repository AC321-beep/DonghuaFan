# ---- CloudStream plugin loader ----
-keep class com.adfree.OptimizerPlugin { *; }
-keepattributes *Annotation*, KotlinMetadata

# ---- Defensive package keep ----
-keep class com.adfree.** { *; }

# ---- androidx.fragment callback subclass ----
# The anonymous object registered via registerFragmentLifecycleCallbacks is
# called by the host at runtime. R8 must not strip it or rename it.
-keep class * extends androidx.fragment.app.FragmentManager$FragmentLifecycleCallbacks { *; }
-keepclassmembers class * extends androidx.fragment.app.FragmentManager$FragmentLifecycleCallbacks {
    public *;
    protected *;
}

# ---- androidx.fragment public API surface ----
# Methods the plugin calls directly (fragments, dismiss, dismissAllowingStateLoss,
# beginTransaction, remove, commitAllowingStateLoss). AndroidX ships its own
# consumer rules, but these are belt-and-braces.
-keep class androidx.fragment.app.FragmentManager { *; }
-keep class androidx.fragment.app.Fragment { *; }
-keep class androidx.fragment.app.FragmentTransaction { *; }
-keep class androidx.fragment.app.DialogFragment { *; }

# ---- Reflective access in PreferenceSanitizer ----
# SharedPreferences, Editor, and the listener are called by the framework
# through reflection-adjacent paths. Keep the entry points.
-keep class android.content.SharedPreferences { *; }
-keep class android.content.SharedPreferences$Editor { *; }
-keep interface android.content.SharedPreferences$OnSharedPreferenceChangeListener { *; }

# ---- Diagnostic log line ----
# Keeps the original class name in the Log.w("AdFree", ...) output so the
# unmatched-dialog diagnostic is readable in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
