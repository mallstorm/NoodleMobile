package pro.noodle.mobile.core.download

data class AdaptiveDownloadSelection(
    val maxVideoWidth: Int = Int.MAX_VALUE,
    val maxVideoHeight: Int = Int.MAX_VALUE,
    val videoLabel: String = "Auto",
    val audioLanguage: String = "",
    val textLanguage: String = "",
    val includeSubtitles: Boolean = false,
) {
    fun normalized(): AdaptiveDownloadSelection = copy(
        maxVideoWidth = maxVideoWidth.coerceAtLeast(1),
        maxVideoHeight = maxVideoHeight.coerceAtLeast(1),
        videoLabel = videoLabel.trim().ifBlank { "Auto" },
        audioLanguage = audioLanguage.trim(),
        textLanguage = textLanguage.trim(),
    )

    fun summary(): String = buildList {
        add(videoLabel.ifBlank { "Auto" })
        if (audioLanguage.isNotBlank()) add("audio=$audioLanguage")
        add(if (includeSubtitles) "subs=${textLanguage.ifBlank { "auto" }}" else "subs=off")
    }.joinToString(" • ")
}
