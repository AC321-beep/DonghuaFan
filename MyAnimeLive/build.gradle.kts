android {
    namespace = "com.myanimelive"
}

cloudstream {
    description = "Anime from myanimelive"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Anime")
    language = "en"
    version = 1
}

dependencies {
    // Use the correct JitPack tag (v0.26.3)
    compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
