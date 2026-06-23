android {
    namespace = "com.myanimelive"
}

cloudstream {
    description = "Chinese Anime (English Sub)"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Anime")
    language = "en"
    version = 5
    iconUrl = "https://static.everythingmoe.com/icons/myanimelive.png"
}

dependencies {
    // ACTIVE FOR COMPILATION: 
    // We keep this active so YoutubeExtractor.kt compiles without errors, 
    // even though the extractor itself is currently dormant in the provider.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
