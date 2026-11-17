package pro.noodle.mobile.core.search

import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.engine.impl.AutoCards
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.EngineSearchItem
import java.net.URI

/**
 * Старые движки («novideo_embed» и др.) берут первую попавшуюся <img> карточки — на живых сайтах это
 * часто заглушка (/static/img/t1.png), а «длительностью» оказывается значок «HD». Универсальный
 * разборщик карточек (как в Studio) находит настоящую картинку и время по тому же адресу видео —
 * подставляем их вместо негодных.
 */
object CardEnricher {
    private val timeRe = Regex("""\d{1,3}:\d{2}(?::\d{2})?""")
    private val placeholders = listOf("/static/", "no_image", "noimage", "placeholder", "blank", "spacer", "loading", "lazy")

    fun looksLikeTime(text: String) = timeRe.containsMatchIn(text)

    fun enrich(
        parsed: List<EngineSearchItem>,
        html: String,
        pageUrl: String,
        adapter: AdapterDefinition,
    ): List<EngineSearchItem> {
        if (parsed.isEmpty() || html.isBlank()) return parsed
        val repeated = parsed.groupingBy { it.thumbnailUrl }.eachCount().filter { it.value >= 3 }.keys
        fun weakThumb(url: String): Boolean {
            if (url.isBlank() || url in repeated) return true
            val u = url.lowercase()
            return u.startsWith("data:") || u.endsWith(".svg") || placeholders.any { it in u }
        }
        fun weakTime(t: String) = !looksLikeTime(t)
        if (parsed.none { weakThumb(it.thumbnailUrl) || weakTime(it.durationText) }) return parsed
        val pattern = adapter.linkPattern.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            ?: parsed.firstNotNullOfOrNull { item ->
                val path = runCatching { URI(EngineParsing.absolute(item.watchPath, pageUrl)).path }.getOrNull()
                path?.takeIf { it.length > 1 }?.let { pth ->
                    runCatching {
                        val body = Regex("""\d+""").split(pth).joinToString("\\d+") { if (it.isEmpty()) "" else Regex.escape(it) }
                        Regex("^$body/?$", RegexOption.IGNORE_CASE)
                    }.getOrNull()
                }
            } ?: return parsed
        val auto = runCatching { AutoCards.extract(html, pageUrl, pattern, 160) }.getOrDefault(emptyList())
        if (auto.isEmpty()) return parsed
        fun key(raw: String) = runCatching {
            URI(EngineParsing.absolute(raw, pageUrl)).path.trimEnd('/').lowercase()
        }.getOrDefault(raw.lowercase())
        val byKey = auto.associateBy { key(it.watchPath) }
        return parsed.map { p ->
            val a = byKey[key(p.watchPath)] ?: return@map p
            p.copy(
                thumbnailUrl = if (weakThumb(p.thumbnailUrl) && a.thumbnailUrl.isNotBlank() && !weakThumb(a.thumbnailUrl)) a.thumbnailUrl else p.thumbnailUrl,
                durationText = if (weakTime(p.durationText) && looksLikeTime(a.durationText)) a.durationText else p.durationText,
                viewsText = p.viewsText.ifBlank { a.viewsText },
            )
        }
    }
}
