package pro.noodle.mobile.core.player

import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.resolver.StreamFacts
import java.net.URI

/**
 * Фильмы, нарезанные на части (`Film.1.mp4`, `Film.2.mp4`… или `Film.1080.1.mp4`…), склеиваются в один
 * «виртуальный» источник: плеер проигрывает их подряд как одно видео с общей длительностью.
 * Части группируются по качеству; если какая-то часть заведомо мертва, группа не склеивается.
 */
object MultiPartJoiner {
    const val PROTOCOL = "concat"
    private val partRe = Regex("""^(.*?)([._\- ](?:part|pt|cd|ch|chast|chast')?[._\- ]?)(\d{1,2})(\.(?:mp4|m4v|webm|mkv|mov))$""", RegexOption.IGNORE_CASE)
    private val qualityRe = Regex("""(?<!\d)(2160|1440|1080|720|480|360)(?!\d)""")

    private data class Part(val n: Int, val source: MediaSource, val quality: Int)

    fun join(sources: List<MediaSource>, facts: Map<String, StreamFacts>): List<MediaSource> {
        val groups = LinkedHashMap<String, MutableList<Part>>()
        for (s in sources) {
            if (s.parts.isNotEmpty() || !s.url.startsWith("https://", true)) continue
            val uri = runCatching { URI(s.url) }.getOrNull() ?: continue
            val path = uri.rawPath.orEmpty()
            val name = path.substringAfterLast('/')
            val m = partRe.find(name) ?: continue
            val prefix = m.groupValues[1]
            val key = uri.host.orEmpty().lowercase() + path.substringBeforeLast('/') + "/" + prefix + m.groupValues[2].lowercase() + "{n}" + m.groupValues[4].lowercase()
            val q = qualityRe.findAll(prefix).lastOrNull()?.value?.toIntOrNull() ?: facts[s.url]?.height ?: 0
            groups.getOrPut(key) { mutableListOf() }.add(Part(m.groupValues[3].toInt(), s, q))
        }
        val out = mutableListOf<MediaSource>()
        for ((_, raw) in groups) {
            val parts = raw.distinctBy { it.n }.sortedBy { it.n }
            if (parts.size < 2 || parts.size > 12) continue
            if (parts.first().n !in 0..1) continue
            if (parts.zipWithNext().any { (a, b) -> b.n != a.n + 1 }) continue // пропуск номера — не склеиваем
            val f = parts.map { facts[it.source.url] }
            if (f.any { it != null && !it.ok }) continue // мёртвая часть — склейка сломается посередине
            if (f.none { it?.ok == true }) continue      // ни одна часть не проверена — не рискуем
            // Короткие «части» (< 3 мин) — это скорее превью/нарезка, а не фильм.
            if (f.any { it != null && it.durationSec in 0.0..180.0 }) continue
            val durations = f.map { fx -> fx?.durationSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: -1L }
            val total = if (durations.all { it > 0 }) durations.sum() else -1L
            val q = parts.first().quality
            val label = buildString {
                append("Весь фильм")
                append(" • ").append(if (q > 0) "${q}p" else "SD")
                append(" • ").append(parts.size).append(" части")
                if (total > 0) append(" • ").append(clock(total))
            }
            out += MediaSource(
                label = label,
                url = parts.first().source.url + "#noodle-parts-" + parts.size,
                mimeType = "video/mp4",
                protocol = PROTOCOL,
                height = q,
                parts = parts.map { it.source.url },
                partDurationsMs = durations,
            )
        }
        return out
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
