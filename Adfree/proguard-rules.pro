# Preserve CloudStream entry points
-keep class com.adfree.OptimizerPlugin { *; }
-keep class com.adfree.TrafficHandler { *; }

# Preserve your own classes (reflection targets)
-keep class com.adfree.** { *; }

# Preserve reflection targets from other plugins
-keep class com.phisher98.donation.** { *; }
-keep class com.cncverse.donation.** { *; }
-keep class *.*DonationManager { *; }
-keep class *.*DonationConfig { *; }
-keep class *.*DonationDialogFragment { *; }

# Preserve Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, MethodParameters, KotlinMetadata
-keepclassmembers class kotlin.Metadata { *; }
