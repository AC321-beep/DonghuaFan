import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.lagradost.cloudstream3.gradle")

android {
    namespace = "com.livesports"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 21
        targetSdk = 35
        
        // These MUST stay here for the main LiveSportsEvents scraper!
        buildConfigField("String", "LIVESPORTS_FIREBASE_API_KEY", "\"${System.getenv("LIVESPORTS_FIREBASE_API_KEY") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_FIREBASE_APP_ID", "\"${System.getenv("LIVESPORTS_FIREBASE_APP_ID") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_FIREBASE_PROJECT_NUMBER", "\"${System.getenv("LIVESPORTS_FIREBASE_PROJECT_NUMBER") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_PROVIDER_SECRET1", "\"${System.getenv("LIVESPORTS_PROVIDER_SECRET1") ?: ""}\"")
        buildConfigField("String", "LIVESPORTS_PROVIDER_SECRET2", "\"${System.getenv("LIVESPORTS_PROVIDER_SECRET2") ?: ""}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
        }
    }
}

cloudstream {
    // Updated to reflect the massive changes we made!
    description = "Premium Live Sports Events & FIFA Streams"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    version = 9 
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/LiveSports/Icon.png"
    ]7"
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
