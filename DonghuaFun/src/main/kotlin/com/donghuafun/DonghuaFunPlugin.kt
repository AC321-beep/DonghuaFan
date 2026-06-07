@CloudstreamPlugin
class DonghuaFunPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaFunProvider())
        registerExtractorAPI(KSRPlayer())
    }
}
