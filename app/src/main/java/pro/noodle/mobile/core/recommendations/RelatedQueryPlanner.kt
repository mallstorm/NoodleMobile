package pro.noodle.mobile.core.recommendations

import pro.noodle.mobile.core.model.VideoItem

/** Short search terms work better than full titles with older SNP-powered search forms. */
object RelatedQueryPlanner {
    private val stop = setOf(
        "video", "watch", "online", "the", "and", "with",
        "для", "как", "это", "что", "или", "про", "при", "без", "его", "нее",
        "она", "они", "где", "когда", "почему", "смотреть", "онлайн", "видео",
        "полное", "бесплатно", "скачать", "качество", "новое", "русское",
    )

    fun queries(video: VideoItem): List<String> {
        fun tokens(s: String): List<String> = Regex("""[\p{L}\p{N}]+""")
            .findAll(s.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in stop && it.any(Char::isLetter) }
            .toList()
        val caption = tokens(video.title).asReversed()
        val tags = video.tags.flatMap(::tokens)
        return (caption + tags).distinct().take(4)
    }
}
