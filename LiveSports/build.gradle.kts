import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Override the incorrect CloudStream dependency from the root
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

// Apply required plugins (root already applies them, but re-applying is safe)
apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.lagradost.cloudstream3.gradle")

// Override the root's namespace
android {
    namespace = "com.livesports"
    compileSdk = 35

    // ENABLE BUILD CONFIG GENERATION
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 21
        targetSdk = 35
        
        // BRIDGE TO GITHUB ACTIONS SECRETS
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
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }
}

// CloudStream metadata (must be after android block)
cloudstream {
    description = "Live Sports (IPTV + Live Events)"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    version = 1
}

// Dependencies – no need to add cloudstream3 here, it's overridden from root
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
