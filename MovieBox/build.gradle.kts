plugins {
    id("com.lagradost.cloudstream3") version "1.0.0"
}

cloudstream {
    description = "Movies, TV Series and Live Sports"
    authors = listOf("AC321-beep")
    tvTypes = listOf("Movie", "TvSeries", "Live")
    language = "en"
    version = 1
    iconUrl = "https://moviebox.ph/favicon.ico"
}
