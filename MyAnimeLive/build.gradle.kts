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
    implementation("com.github.TeamNewPipe:NewPipeExtractor:0.24.1")
}
