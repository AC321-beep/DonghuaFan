package com.chikianimation

class ChikiAnimation3D : ChikiAnimationProvider() {
    override var name = "ChikiAnimation (3D)"
    override var mainUrl = "https://chikianimation.online"

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update"           to "Latest Release",
        "anime/?status=&type=&order=popular"          to "Popular",
        "anime/?status=&type=ai+donghua&order=update" to "AI Donghua", // 3D variant
        "anime/?status=ongoing&type=&order=update"    to "Ongoing",
        "anime/?status=completed&type=&order=update"  to "Completed",
        "anime/?status=&type=movie&order=update"      to "Movies"
    )
}
