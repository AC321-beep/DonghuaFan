# ---- CloudStream plugin loader ----
-keep class com.adfree.OptimizerPlugin { *; }
-keepattributes *Annotation*, KotlinMetadata

# ---- Defensive package keep ----
-keep class com.adfree.** { *; }
