// ── DonghuaFun/build.gradle.kts ─────────────────────────────────────────────
// Drop this file into:  DonghuaFun/build.gradle.kts
// (rename the ExampleProvider folder to DonghuaFun first)

version = 1   // bump this on every release

cloudstream {
    // Display name shown inside the CloudStream app
    description = "Watch Donghua (Chinese Animation) from donghuafun.com"

    // Authors list shown in the repo browser
    authors = listOf("AC321-beep")

    /**
     * Status codes:
     *   0  = Down / broken
     *   1  = Working
     *   2  = Slow / sometimes down
     *   3  = Outdated – still works, but expect issues
     */
    status = 1

    tvTypes = listOf("Anime")

    language = "zh"

    iconUrl = "https://donghuafun.com/template/shoutu45/assets/images/logo-1.png"
}
