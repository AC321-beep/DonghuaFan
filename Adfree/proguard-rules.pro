# 1. PRESERVE THE CLOUDSTREAM PLUGIN ENTRY POINT
-keep class com.adfree.OptimizerPlugin { *; }
-keep class com.adfree.TrafficHandler { *; }

# 2. PRESERVE YOUR OWN PLUGIN'S CLASSES (For Reflection)
-keep class com.adfree.** { *; }

# 3. PRESERVE REFLECTION TARGETS (Donation & Ad Managers)
-keep class com.phisher98.donation.** { *; }
-keep class com.cncverse.donation.** { *; }
-keep class *.*DonationManager { *; }
-keep class *.*DonationConfig { *; }
-keep class *.*DonationDialogFragment { *; }
-keep class *.*AdManager { *; }
-keep class *.*AdConfig { *; }
-keep class *.*PopupManager { *; }

# 4. PRESERVE KOTLIN METADATA (Vital for Reflection)
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, MethodParameters, KotlinMetadata
-keepclassmembers class kotlin.Metadata { *; }

# 5. AGGRESSIVE REPACKAGING (Hides your classes from runtime detection)
-repackageclasses 'o'
