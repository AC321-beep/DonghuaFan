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
    // Use the correct JitPack tag (v0.27.1)
    compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
}
