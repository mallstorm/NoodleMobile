import pro.noodle.mobile.core.download.AdaptiveDownloadSelection

private var passed = 0
private var failed = 0
private fun check(name: String, condition: Boolean) {
    if (condition) { passed++; println("PASS | $name") }
    else { failed++; println("FAIL | $name") }
}

fun main() {
    val auto = AdaptiveDownloadSelection().normalized()
    check("Auto width unlimited", auto.maxVideoWidth == Int.MAX_VALUE)
    check("Auto height unlimited", auto.maxVideoHeight == Int.MAX_VALUE)
    check("Auto label", auto.videoLabel == "Auto")
    check("Auto subtitles disabled", !auto.includeSubtitles)
    check("Invalid width clamps", AdaptiveDownloadSelection(0, 720).normalized().maxVideoWidth == 1)
    check("Invalid height clamps", AdaptiveDownloadSelection(1280, -1).normalized().maxVideoHeight == 1)
    val selected = AdaptiveDownloadSelection(1280, 720, " 720p ", " ru ", " en ", true).normalized()
    check("Label trimmed", selected.videoLabel == "720p")
    check("Audio language trimmed", selected.audioLanguage == "ru")
    check("Text language trimmed", selected.textLanguage == "en")
    check("Subtitle flag preserved", selected.includeSubtitles)
    check("Summary quality", selected.summary().contains("720p"))
    check("Summary audio", selected.summary().contains("audio=ru"))
    check("Summary subtitles", selected.summary().contains("subs=en"))
    check("Off summary", auto.summary().contains("subs=off"))
    val autoSubs = AdaptiveDownloadSelection(includeSubtitles = true).normalized()
    check("Auto subtitle summary", autoSubs.summary().contains("subs=auto"))
    println("SUMMARY $passed/${passed + failed} passed")
    if (failed != 0) error("Stage6 harness failed: $failed")
}
