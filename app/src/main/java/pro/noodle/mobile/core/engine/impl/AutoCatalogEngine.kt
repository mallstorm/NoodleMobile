package pro.noodle.mobile.core.engine.impl

import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.engine.runtime.EngineResolveResult
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.EngineSearchItem
import pro.noodle.mobile.core.engine.runtime.SourceEngine

/**
 * Universal catalogue engine produced by SNP Studio auto-builder.
 *
 * Instead of fragile CSS selectors it stores one learned path regex (`search.link_pattern`) that
 * identifies video-card links on a search page. Each matching anchor is expanded into a card window
 * (the anchor itself plus nearby markup up to the next card), from which preview, title, duration
 * and views are inferred. This survives class renames, nested layouts and most CMS skins.
 */
class AutoCatalogEngine : SourceEngine {
    override val id = "auto_catalog"
    override val aliases = setOf("auto", "smart_catalog", "universal_catalog")

    override fun buildSearchUrl(request: EngineSearchRequest): String = ""

    override fun parseSearch(text: String, request: EngineSearchRequest): List<EngineSearchItem> {
        if (text.isBlank() || request.linkPattern.isBlank()) return emptyList()
        val pattern = runCatching { Regex(request.linkPattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
        return AutoCards.extract(text, request.baseUrl, pattern, request.maxItems.coerceAtLeast(1))
    }

    override fun resolvePlayback(text: String, pageUrl: String): EngineResolveResult {
        // Order matters: KVS flashvars carry the canonical quality ladder; generic player configs
        // cover the long tail of JS players; window.playlist pages are handled by jwplayer_config.
        val kvs = KvsPlaybackResolver.resolve(text, pageUrl)
        if (kvs.success) return kvs
        val player = GenericPlayerConfigEngine().resolvePlayback(text, pageUrl)
        if (player.success) return player
        val html5 = GenericHtml5Engine().resolvePlayback(text, pageUrl)
        return if (html5.success) html5 else player.takeIf { it.handled } ?: html5
    }
}

/** Shared, dependency-free card extraction used both by the runtime engine and by Studio. */
object AutoCards {
    private val anchorRe = Regex("<a\\b([^>]*)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val imgRe = Regex("<(?:img|source)\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val durationRe = Regex("(?<![\\d:])(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)(?![\\d:])")
    private val viewsRe = Regex("(\\d[\\d\\s.,]*\\s?[KkMmКкМм]?)\\s*(?:views?|просмотр\\w*)", RegexOption.IGNORE_CASE)
    private val imageAttrs = listOf("data-original", "data-src", "data-lazy-src", "data-thumb", "data-webp", "data-preview", "data-poster", "poster", "srcset", "data-srcset", "src")

    data class Anchor(val start: Int, val end: Int, val href: String, val path: String, val attrs: String)

    /** Every `<a href>` in the document, resolved against [baseUrl]. */
    fun anchors(html: String, baseUrl: String): List<Anchor> = anchorRe.findAll(html).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val raw = EngineParsing.attr("<a $attrs>", "href")
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true)) return@mapNotNull null
        val abs = EngineParsing.absolute(raw, baseUrl)
        if (!abs.startsWith("http", true)) return@mapNotNull null
        val path = pathOf(abs)
        val close = html.indexOf("</a>", m.range.last, ignoreCase = true).let { if (it < 0) m.range.last + 1 else it + 4 }
        Anchor(m.range.first, close, abs, path, attrs)
    }.toList()

    fun extract(html: String, baseUrl: String, pattern: Regex, max: Int): List<EngineSearchItem> {
        val baseHost = hostOf(baseUrl)
        val all = anchors(html, baseUrl)
        val matching = all.filter { a -> pattern.containsMatchIn(a.path) && sameSite(hostOf(a.href), baseHost) }
        if (matching.isEmpty()) return emptyList()
        // Group repeated anchors (thumbnail link + title link) of the same card.
        val firstIndex = linkedMapOf<String, MutableList<Anchor>>()
        matching.forEach { firstIndex.getOrPut(it.href.substringBefore('#')) { mutableListOf() }.add(it) }
        val starts = firstIndex.values.map { it.first().start }.sorted()
        val out = mutableListOf<EngineSearchItem>()
        for ((href, group) in firstIndex) {
            val start = group.first().start
            val nextStart = starts.firstOrNull { it > start } ?: (start + 4000)
            val lastEnd = group.maxOf { it.end }
            val end = maxOf(lastEnd, minOf(nextStart, start + 4000)).coerceAtMost(html.length)
            var window = html.substring(start, end)
            var thumb = firstImage(window, baseUrl)
            if (thumb.isBlank()) {
                // Some skins place the preview right before the link (sibling layout).
                val back = html.substring((start - 900).coerceAtLeast(0), start)
                val prevCard = starts.lastOrNull { it < start } ?: 0
                val scoped = if (prevCard > start - 900) html.substring(maxOf(prevCard, (start - 900).coerceAtLeast(0)), start) else back
                thumb = firstImage(scoped, baseUrl)
                if (thumb.isNotBlank()) window = scoped + window
            }
            val title = titleOf(group, window, html)
            val plain = EngineParsing.stripTags(window)
            val duration = durationRe.find(plain)?.value.orEmpty()
            val views = viewsRe.find(plain)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val remote = remoteId(href)
            out += EngineSearchItem(remote, href, title.ifBlank { remote.replace('-', ' ').replace('_', ' ') }, thumb, duration, views)
            if (out.size >= max) break
        }
        // Navigation pages that share the card URL shape ("Поиск по сайту", "Топ 100"…) have neither a
        // preview nor a duration. Drop them as soon as the page clearly has real video cards.
        if (out.count { it.thumbnailUrl.isNotBlank() } >= 3) {
            val real = out.filter { it.thumbnailUrl.isNotBlank() || it.durationText.isNotBlank() }
            if (real.size >= 3) return real
        }
        return out
    }

    /** True when the text of a card window contains a video duration like 12:34 or 1:02:11. */
    fun hasDuration(block: String): Boolean = durationRe.containsMatchIn(EngineParsing.stripTags(block))

    fun firstImage(block: String, baseUrl: String): String {
        for (m in imgRe.findAll(block)) {
            val tag = m.value
            for (attr in imageAttrs) {
                var v = EngineParsing.attr(tag, attr)
                if (v.isBlank()) continue
                if (attr.contains("srcset")) v = v.split(',').map { it.trim().substringBefore(' ') }.lastOrNull { it.isNotBlank() }.orEmpty()
                if (v.startsWith("data:", true) || placeholder(v)) continue
                val abs = EngineParsing.absolute(v, baseUrl)
                if (abs.startsWith("http", true)) return abs
            }
        }
        // CSS background previews: style="background-image:url(...)"
        Regex("url\\((['\"]?)([^)'\"]+)\\1\\)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(2)?.let {
            if (!placeholder(it)) return EngineParsing.absolute(it, baseUrl)
        }
        return ""
    }

    private fun titleOf(group: List<Anchor>, window: String, html: String): String {
        group.forEach { a -> EngineParsing.attr("<a ${a.attrs}>", "title").takeIf { looksLikeTitle(it) }?.let { return it } }
        group.forEach { a -> EngineParsing.attr("<a ${a.attrs}>", "aria-label").takeIf { looksLikeTitle(it) }?.let { return it } }
        Regex("<(?:h[1-6]|strong|span|div|p)[^>]*class=[\"'][^\"']*(?:title|name|caption)[^\"']*[\"'][^>]*>([\\s\\S]{1,400}?)</(?:h[1-6]|strong|span|div|p)>", RegexOption.IGNORE_CASE)
            .find(window)?.groupValues?.getOrNull(1)?.let(EngineParsing::stripTags)?.let(::cleanTitle)?.takeIf { looksLikeTitle(it) }?.let { return it }
        imgRe.findAll(window).forEach { m -> EngineParsing.attr(m.value, "alt").takeIf { looksLikeTitle(it) }?.let { return it } }
        group.forEach { a ->
            val inner = cleanTitle(EngineParsing.stripTags(html.substring(a.start, a.end.coerceAtMost(html.length))))
            if (looksLikeTitle(inner)) return inner.take(200)
        }
        imgRe.findAll(window).forEach { m -> EngineParsing.attr(m.value, "title").takeIf { looksLikeTitle(it) }?.let { return it } }
        return ""
    }

    /** Drops durations, view counters and stray numbers that skins put inside the card link. */
    private fun cleanTitle(v: String): String = v.replace(durationRe, " ")
        .replace(Regex("(?:^|\\s)\\d[\\d\\s.,]*[KkMmКкМм%]?(?=\\s|$)"), " ")
        .replace(Regex("\\b(?:HD|4K|VR|NEW)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun looksLikeTitle(v: String): Boolean = v.count { it.isLetter() } >= 3

    private fun placeholder(v: String): Boolean {
        val l = v.lowercase()
        return l.endsWith(".svg") || l.contains("blank.") || l.contains("placeholder") || l.contains("lazy.") || l.contains("spacer") || l.contains("1x1") || l.contains("pixel.gif")
    }

    fun remoteId(href: String): String {
        val path = pathOf(href).trimEnd('/')
        val digits = Regex("/(\\d{3,})(?:[/_-]|$)").find(path)?.groupValues?.getOrNull(1)
        return digits ?: path.substringAfterLast('/').substringBefore('.').ifBlank { path.hashCode().toUInt().toString(16) }
    }

    fun pathOf(url: String): String = runCatching { java.net.URI(url).rawPath.orEmpty().ifBlank { "/" } }.getOrDefault(url.substringAfter("://").substringAfter('/', "").let { "/$it" }.substringBefore('?'))
    fun hostOf(url: String): String = runCatching { java.net.URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
    fun sameSite(host: String, base: String): Boolean = host.isNotBlank() && (host == base || host.endsWith(".$base") || base.endsWith(".$host"))
}
