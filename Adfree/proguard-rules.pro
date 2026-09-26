# Preserve CloudStream entry points
-keep class com.adfree.OptimizerPlugin { *; }
-keep class com.adfree.TrafficHandler { *; }

# Preserve your own plugin's classes (reflection targets)
-keep class com.adfree.** { *; }

# Preserve Kotlin metadata (needed for reflection on Kotlin objects)
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, MethodParameters, KotlinMetadata
-keepclassmembers class kotlin.Metadata { *; }
