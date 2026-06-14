android {
    namespace = "com.myanimelive"
}

cloudstream {
    description = "Chinese Anime (English Sub)"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Anime")
    language = "en"
    version = 4
    iconUrl = "https://myanime.live/favicon.ico"
}

dependencies {
    // ARCHIVED: Custom NewPipe Extractor Dependency
    // Uncomment the line below if the built-in Cloudstream extractor breaks 
    // and you need to bundle a specific standalone NewPipe version again.
    // implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
