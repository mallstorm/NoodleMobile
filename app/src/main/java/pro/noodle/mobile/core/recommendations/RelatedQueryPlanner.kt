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
        val caption = tokens(video.title) // Lead with the subject, not incidental words at the title's end
        val tags = video.tags.flatMap(::tokens)
        return (caption + tags).distinct().take(4)
    }

    /** Расширенный набор запросов: одиночные слова + пары соседних слов заголовка + теги. */
    fun expanded(video: VideoItem): List<String> {
        val words = Regex("""[\p{L}\p{N}]+""").findAll(video.title.lowercase()).map { it.value }
            .filter { it.length >= 3 && it !in stop && it.any(Char::isLetter) }.toList()
        val pairs = words.zipWithNext { a, b -> "$a $b" }
        val tagWords = video.tags.flatMap { t ->
            Regex("""[\p{L}\p{N}]+""").findAll(t.lowercase()).map { it.value }.filter { it.length >= 3 && it !in stop }.toList()
        }
        return (queries(video) + pairs.take(3) + words + tagWords).distinct().take(10)
    }
}
