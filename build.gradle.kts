import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

// =========================================================================
//  DYNAMIC VERSION RESOLVER (runtime deps only — see note at bottom)
// =========================================================================
class VersionResolver(private val cacheFile: File) {
    private val ttlMs = 24L * 60 * 60 * 1000
    private val props = java.util.Properties().apply {
        if (cacheFile.exists()) cacheFile.reader().use { load(it) }
    }
    private var dirty = false

    private fun httpGet(url: String, timeoutMs: Int = 5000): String {
        val conn = java.net.URI(url).toURL().openConnection().apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "gradle-version-resolver")
        }
        return conn.getInputStream().bufferedReader().use { it.readText() }
    }

    private fun mavenMetadata(group: String, artifact: String, baseUrl: String): String {
        val path = group.replace('.', '/')
        val url = "$baseUrl/$path/$artifact/maven-metadata.xml"
        val text = httpGet(url)
        return Regex("""<release>([^<]+)</release>""").find(text)?.groupValues?.get(1)
            ?: Regex("""<latest>([^<]+)</latest>""").find(text)?.groupValues?.get(1)
            ?: error("no release in maven-metadata for $group:$artifact")
    }

    private fun resolve(key: String, fallback: String, fetch: () -> String): String {
        val ts = props.getProperty("$key.time")?.toLongOrNull() ?: 0L
        val cached = props.getProperty(key)
        if (cached != null && System.currentTimeMillis() - ts < ttlMs) {
            println("[versions] $key = $cached (cached)")
            return cached
        }
        val resolved = try {
            fetch().also { println("[versions] $key = $it (fetched)") }
        } catch (e: Exception) {
            println("[versions] $key lookup failed: ${e.message}; using ${cached ?: fallback}")
            cached ?: fallback
        }
        if (resolved != cached) dirty = true
        props.setProperty(key, resolved)
        props.setProperty("$key.time", System.currentTimeMillis().toString())
        return resolved
    }

    fun central(group: String, artifact: String, fallback: String): String =
        resolve("central:$group:$artifact", fallback) {
            mavenMetadata(group, artifact, "https://repo1.maven.org/maven2")
        }

    fun google(group: String, artifact: String, fallback: String): String =
        resolve("google:$group:$artifact", fallback) {
            mavenMetadata(group, artifact, "https://dl.google.com/dl/android/maven2")
        }

    fun flush() {
        if (!dirty) return
        cacheFile.parentFile.mkdirs()
        cacheFile.writer().use { props.store(it, "Auto-resolved. Delete to force refresh.") }
    }
}

val versionResolver = VersionResolver(File(rootDir, ".gradle/version-cache.properties"))

val coroutinesVersion    = versionResolver.central("org.jetbrains.kotlinx",       "kotlinx-coroutines-core",     "1.11.0")
val serializationVersion = versionResolver.central("org.jetbrains.kotlinx",       "kotlinx-serialization-json",  "1.11.0")
val okhttpVersion        = versionResolver.central("com.squareup.okhttp3",         "okhttp",                      "5.4.0")
val jsoupVersion         = versionResolver.central("org.jsoup",                    "jsoup",                       "1.23.1")
val jacksonVersion       = versionResolver.central("com.fasterxml.jackson.module", "jackson-module-kotlin",       "2.22.1")
val browserVersion       = versionResolver.google ("androidx.browser",             "browser",                     "1.10.0")
val annotationVersion    = versionResolver.google ("androidx.annotation",          "annotation",                  "1.10.0")
val rhinoVersion         = versionResolver.central("org.mozilla",                  "rhino",                       "1.8.1")

// =========================================================================
//  BUILDSCRIPT — versions must be literal here.
//
//  Reason: Gradle compiles the `buildscript { }` block into its own pass
//  that runs BEFORE the rest of this file. Top-level `val`s declared below
//  or above it are not in scope. Making these dynamic would require a
//  `buildSrc/` module or a properties file read by settings.gradle.kts —
//  not worth it since AGP/Kotlin/Gradle-plugin versions change at most a
//  few times per year.
// =========================================================================
buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
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
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
        authors = listOf("megix")
    }

    android {
        namespace = "com.example"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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

        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))

        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

        implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:$jsoupVersion")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

        implementation("androidx.browser:browser:$browserVersion")
        implementation("androidx.annotation:annotation:$annotationVersion")
        implementation("org.mozilla:rhino:$rhinoVersion")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

gradle.projectsEvaluated { versionResolver.flush() }
