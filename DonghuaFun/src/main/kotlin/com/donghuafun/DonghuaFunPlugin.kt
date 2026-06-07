@CloudstreamPlugin
class DonghuaFunPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaFunProvider())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(waaw())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Ultrahd())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(PlayStreamplay())
    }
}
