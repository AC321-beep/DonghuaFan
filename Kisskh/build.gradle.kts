version = 2

android {
    defaultConfig {
    }
}

cloudstream {
    description = "Mostly Asian Content "
    authors = listOf(providers.gradleProperty("cs_author").orNull ?: "AC321-beep")
    status = 1
    tvTypes = listOf("AsianDrama", "Anime")
    language = "en"
    iconUrl = "https://kisskh.nl/favicon.ico"
}
