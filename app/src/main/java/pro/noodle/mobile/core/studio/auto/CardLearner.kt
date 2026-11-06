package pro.noodle.mobile.core.studio.auto

import pro.noodle.mobile.core.engine.impl.AutoCards

/**
 * Learns which links on a listing page are video cards.
 *
 * Links are clustered by "path shape" (`/video/123/some-slug/` → `/video/#/<slug>/`). The cluster with the
 * most distinct links that carry previews and look like media pages wins, and is turned into an
 * anchored path regex for the auto_catalog engine.
 */
object CardLearner {
    data class Learned(val pattern: String, val shape: String, val distinct: Int, val withImages: Int, val score: Int)

    private val mediaWords = setOf(
        "video", "videos", "watch", "view", "v", "vid", "vids", "embed", "movie", "movies", "clip", "clips",
        "play", "player", "film", "films", "post", "porn", "xxx", "tube", "stream", "episode", "media", "item",
    )
    private val navWords = setOf(
        "tag", "tags", "category", "categories", "cat", "cats", "channel", "channels", "model", "models", "pornstar",
        "pornstars", "star", "stars", "actor", "actors", "user", "users", "member", "members", "profile", "playlist",
        "playlists", "search", "page", "pages", "site", "sites", "studio", "studios", "network", "networks", "photo",
        "photos", "album", "albums", "gallery", "galleries", "pics", "blog", "news", "login", "signup", "register",
        "account", "dmca", "terms", "privacy", "contact", "about", "faq", "help", "support", "upload", "premium", "join",
        "static", "assets", "cdn", "api", "rss", "feed", "lang", "language", "top", "popular", "latest", "new", "trending",
        "best", "random", "longest", "most-viewed", "top-rated", "hd", "live", "cams", "games", "dating",
    )
    private val fileExt = Regex("\\.(?:jpe?g|png|gif|webp|svg|css|js|ico|xml|json|txt|zip|rar|pdf)$", RegexOption.IGNORE_CASE)

    fun learn(html: String, pageUrl: String, query: String = ""): Learned? {
        val base = SnpAutoBuilder.originOf(pageUrl)
        val baseHost = SnpAutoBuilder.registrable(SnpAutoBuilder.hostOf(base))
        val anchors = AutoCards.anchors(html, base).filter { a ->
            AutoCards.sameSite(AutoCards.hostOf(a.href), baseHost) && a.path.length > 1 && !fileExt.containsMatchIn(a.path) && a.path.length < 260
        }
        if (anchors.isEmpty()) return null
        val groups = linkedMapOf<String, MutableList<AutoCards.Anchor>>()
        anchors.forEach { a -> shape(a.path)?.let { groups.getOrPut(it) { mutableListOf() }.add(a) } }
        val tokens = SnpAutoBuilder.queryTokens(query)

        val ranked = groups.mapNotNull { (shape, list) ->
            val distinct = list.map { it.href.substringBefore('#').substringBefore('?') }.distinct()
            if (distinct.size < 3) return@mapNotNull null
            val firstSeg = shape.trim('/').substringBefore('/')
            if (firstSeg in navWords) return@mapNotNull null
            val byHref = list.groupBy { it.href.substringBefore('#').substringBefore('?') }
            val withImages = byHref.count { (_, same) ->
                same.any { a -> AutoCards.firstImage(html.substring(a.start, minOf(html.length, maxOf(a.end, a.start + 1200))), base).isNotBlank() }
            }
            val relevant = if (tokens.isEmpty()) 0 else distinct.count { h -> tokens.any { h.lowercase().contains(it) } }
            var score = distinct.size.coerceAtMost(60) * 3 + withImages * 5 + relevant * 2
            if (firstSeg in mediaWords) score += 40
            if (shape.contains('#')) score += 15
            if (shape.count { it == '/' } >= 2) score += 5
            val imageRatio = withImages.toDouble() / distinct.size
            if (imageRatio < 0.3) score -= 60
            if (shape == "/*" || shape == "/*/") {
                // Root-level slugs are common for tubes but also for menus; require stronger evidence.
                if (imageRatio < 0.6) score -= 40
                score -= 10
            }
            Learned(toRegex(shape), shape, distinct.size, withImages, score)
        }
        return ranked.filter { it.score > 0 }.maxByOrNull { it.score }
    }

    /**
     * `/video/12345/nice-title/` → `/video/#/<slug>/` ; `/12345.html` → `/#.html` ; `/watch/-1939_4562` → `/watch/<slug>`.
     * (`<slug>` is stored as the token "*" in the shape string.)
     * Returns null for paths that obviously aren't item pages.
     */
    fun shape(path: String): String? {
        val trailing = path.endsWith('/')
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > 5) return null
        val shaped = segments.mapIndexed { index, seg ->
            val ext = Regex("\\.(html?|php)$", RegexOption.IGNORE_CASE).find(seg)?.value.orEmpty()
            val core = seg.removeSuffix(ext)
            val token = when {
                core.matches(Regex("\\d+")) -> "#"
                index == 0 && segments.size > 1 && core.matches(Regex("[A-Za-z][A-Za-z-]{0,23}")) -> core.lowercase()
                index == 0 && segments.size == 1 -> {
                    // A lone root slug must look like an item (digits or a long dashed slug), not a menu word.
                    if (core.lowercase() in navWords || core.lowercase() in mediaWords) return null
                    if (!(core.any(Char::isDigit) || core.count { it == '-' || it == '_' } >= 2)) return null
                    "*"
                }
                else -> "*"
            }
            token + ext.lowercase()
        }
        if (segments.size == 1 && shaped[0].startsWith("#") && segments[0].length < 3) return null
        return "/" + shaped.joinToString("/") + if (trailing) "/" else ""
    }

    fun toRegex(shape: String): String {
        val body = shape.trim('/').split('/').joinToString("/") { seg ->
            val ext = Regex("\\.(html?|php)$").find(seg)?.value.orEmpty()
            val core = seg.removeSuffix(ext)
            val part = when (core) {
                "#" -> "\\d+"
                "*" -> "[^/]+"
                else -> Regex.escape(core).let { if (it.startsWith("\\Q")) core.replace("-", "\\-") else it }
            }
            part + if (ext.isNotEmpty()) "\\" + ext else ""
        }
        return "^/$body/?$"
    }
}
