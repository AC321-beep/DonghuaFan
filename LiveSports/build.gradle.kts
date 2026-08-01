plugins {
    id("com.android.library")
    id("com.lagradost.cloudstream3.gradle")
    // REMOVED: kotlin("android") / apply(plugin = "kotlin-android") - Fatal in AGP 9.0+
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group == "com.lagradost" && requested.name == "cloudstream3") {
                useTarget("com.github.recloudstream:cloudstream:pre-release")
                because("Official CloudStream API on JitPack")
            }
        }
    }
}

// REMOVED: import com.android.build.gradle.BaseExtension (Deprecated/Causes compilation script error)
// REMOVED: import org.jetbrains.kotlin.gradle.dsl.JvmTarget (Redundant)

android {
    namespace = "com.livesports"
    
    // minSdk, compileSdk, compileOptions, and KotlinCompile tasks are removed 
    // because your root build.gradle.kts already automatically applies them to all submodules.

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // REMOVED: targetSdk = 35 (Library modules do not support this in AGP 9.0+)
        
        // These MUST stay here for the main LiveSportsEvents scraper!
        buildConfigField("String", "LIVESPORTS_FIREBASE_API_KEY", "\"${System.getenv("LIVESPORTS_FIREBASE_API_KEY") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_FIREBASE_APP_ID", "\"${System.getenv("LIVESPORTS_FIREBASE_APP_ID") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_FIREBASE_PROJECT_NUMBER", "\"${System.getenv("LIVESPORTS_FIREBASE_PROJECT_NUMBER") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_PROVIDER_SECRET1", "\"${System.getenv("LIVESPORTS_PROVIDER_SECRET1") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_PROVIDER_SECRET2", "\"${System.getenv("LIVESPORTS_PROVIDER_SECRET2") ?: ""}\"")
    }
}

cloudstream {
    description = "Premium Live Sports Events (Livesports + SportsZone)"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    version = 11 
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/LiveSports/Icon.png"
}

dependencies {
    // Module-specific UI/Component dependencies
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    
    // Note: okhttp, jackson, and jsoup are technically already provided globally by your root file, 
    // but leaving them here is perfectly fine to ensure module independence.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
