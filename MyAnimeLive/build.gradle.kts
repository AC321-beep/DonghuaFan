android {
    namespace = "com.myanimelive"
}

cloudstream {
    description = "Chinese Anime (English Sub)"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Anime")
    language = "en"
    version = 2
}

dependencies {
    // NewPipe extractor - must be implementation not compileOnly
    // so the classes are available at runtime
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
