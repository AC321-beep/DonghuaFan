import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // Safe, tested version that works with your GitHub Actions runner
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        // Latest stable Kotlin tooling release
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10") 
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = 
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) = 
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
        authors = listOf("megix")
    }

    android {
        namespace = "com.example" // Your dynamic fallback
        compileSdk = 36 
        
        defaultConfig {
            minSdk = 21
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                listOf(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            )
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Sticking to pre-release to avoid 429/404 Jitpack errors
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        
        // --- LATEST 2026 DEPENDENCIES ---
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        
        implementation("com.squareup.okhttp3:okhttp:5.4.0")
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.23.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
        
        implementation("androidx.browser:browser:1.10.0")
        implementation("androidx.annotation:annotation:1.10.0")
        implementation("org.mozilla:rhino:1.8.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
