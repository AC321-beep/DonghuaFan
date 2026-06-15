import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Apply the CloudStream plugin (if not already applied by root)
plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.livesports"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35

        // Optional: BuildConfig fields for secrets (add later when you have them)
        // buildConfigField("String", "LIVESPORTS_FIREBASE_API_KEY", "\"\"")
        // buildConfigField("String", "LIVESPORTS_FIREBASE_APP_ID", "\"\"")
        // buildConfigField("String", "LIVESPORTS_FIREBASE_PROJECT_NUMBER", "\"\"")
        // buildConfigField("String", "LIVESPORTS_PROVIDER_SECRET1", "\"\"")
        // buildConfigField("String", "LIVESPORTS_PROVIDER_SECRET2", "\"\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }
}

// ✅ CRITICAL: CloudStream extension metadata
cloudstream {
    description = "Live Sports (IPTV + Live Events)"
    authors = listOf("AC321-beep")
    status = 1                          // 1 = Working
    tvTypes = listOf("Live")            // Live TV channels + events
    language = "en"                     // English
    version = 1                         // Integer version code (increment on updates)
    versionName = "1.0.0"               // Optional semantic version
    iconUrl = "https://your-icon-url.png"  // Replace with actual icon if you have one
}

dependencies {
    // CloudStream3 core (provided at runtime)
    compileOnly("com.lagradost:cloudstream3:pre-release")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AndroidX (for SettingsDialog)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // JSON parsing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")

    // HTML parsing (if needed)
    implementation("org.jsoup:jsoup:1.18.3")
}
