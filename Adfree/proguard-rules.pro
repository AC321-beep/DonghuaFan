# =====================================================================
# 1. PRESERVE THE CLOUDSTREAM PLUGIN ENTRY POINT
# =====================================================================
# If this class is obfuscated, CloudStream will fail to load your plugin entirely.
-keep class com.net.optimizer.OptimizerPlugin { *; }
-keep class com.net.optimizer.TrafficHandler { *; }

# =====================================================================
# 2. PRESERVE YOUR OWN PLUGIN'S CLASSES
# =====================================================================
# You use reflection on your own classes (SettingsDialog, FilterStore, SystemInterceptor).
# Preserve all of them so reflection works.
-keep class com.net.optimizer.** { *; }

# =====================================================================
# 3. PRESERVE REFLECTION TARGETS (DONATION & AD MANAGERS)
# =====================================================================
# Your dynamic blocker uses Class.forName() and getDeclaredField() to find these.
# If they are renamed by R8, your blocker will silently fail.

# Known targets from Phisher98 and CNCVerse
-keep class com.phisher98.donation.** { *; }
-keep class com.cncverse.donation.** { *; }

# Generic patterns to catch future obfuscated targets
-keep class *.*DonationManager { *; }
-keep class *.*DonationConfig { *; }
-keep class *.*DonationDialogFragment { *; }
-keep class *.*AdManager { *; }
-keep class *.*AdConfig { *; }
-keep class *.*PopupManager { *; }

# =====================================================================
# 4. PRESERVE KOTLIN METADATA (VITAL FOR REFLECTION)
# =====================================================================
# Your code uses reflection to inspect Kotlin properties (getDeclaredField).
# Without these attributes, reflection on Kotlin objects (like DonationManager.INSTANCE) will fail.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, MethodParameters, KotlinMetadata
-keepclassmembers class kotlin.Metadata { *; }
