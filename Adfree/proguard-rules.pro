# ---- CloudStream plugin loader ----
-keepattributes *Annotation*, KotlinMetadata

# ---- Defensive package keep ----
# This single rule protects OptimizerPlugin, FilterStore, PopupBlocker, and SettingsDialog
-keep class com.adfree.** { *; }

# ---- androidx.fragment callback subclass ----
# The anonymous object registered via registerFragmentLifecycleCallbacks is
# compiled into the plugin. R8 must not strip it or rename it.
-keep class * extends androidx.fragment.app.FragmentManager$FragmentLifecycleCallbacks { *; }
-keepclassmembers class * extends androidx.fragment.app.FragmentManager$FragmentLifecycleCallbacks {
    public *;
    protected *;
}

# ---- Diagnostic log line ----
# Keeps the original class name in the Log.w("AdFree", ...) output so the
# unmatched-dialog diagnostic is readable in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
