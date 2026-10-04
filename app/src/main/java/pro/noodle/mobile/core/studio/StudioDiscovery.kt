package pro.noodle.mobile.core.studio

import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal data class StudioDiscoveryResult(
    val success: Boolean,
    val finalUrl: String = "",
    val query: String = "",
    val draft: AdapterStudioDraft? = null,
    val localJson: String = "",
    val searchHtml: String = "",
    val searchUrl: String = "",
    val searchTemplate: String = "",
    val viewHtml: String = "",
    val viewUrl: String = "",
    val iframeHtml: String = "",
    val iframeUrl: String = "",
    val engine: String = "",
    val score: Int = 0,
    val summary: String = "",
    val evidence: String = "",
    val errorCode: String = "",
    val message: String = "",
)

/** Delphi AdapterGenerator-style deterministic discovery for Studio 2.1. */
internal class StudioDiscovery(private val http: SecureHttpClient) {
    suspend fun discover(url: String, query: String = ""): StudioDiscoveryResult {
        val input = normalizeHttps(url)
            ?: return fail("STUDIO_URL_INVALID", "Некорректный HTTPS URL.")
        val uri = URI(input)
        val host = uri.host.lowercase()
        val base = originOf(input)
        val probeQuery = query.trim().ifBlank { fallbackQuery(input) }
        val probes = mutableListOf<SearchProbe>()

        for (spec in candidates()) {
            val target = render(spec, base, probeQuery)
            val net = http.fetchText(
                target,
                NetworkPolicy(listOf(host, "*.$host"), maxResponseBytes = 6 * 1024 * 1024, maxRedirects = 4),
                SOURCE_KEY,
            )
            if (!net.success) {
                probes += SearchProbe(spec, target, status = net.statusCode, loaded = false, score = -1000)
                continue
            }
            val signals = StudioDiscoveryHeuristics.inspectSearch(net.text)
            probes += SearchProbe(
                spec = spec,
                requestedUrl = target,
                finalUrl = net.finalUrl,
                html = net.text,
                status = net.statusCode,
                loaded = true,
                signals = signals,
                score = StudioDiscoveryHeuristics.scoreSearch(signals, net.text, probeQuery, target),
            )
        }

        val best = probes.filter { it.loaded }
            .sortedWith(compareByDescending<SearchProbe> { it.score }.thenBy { it.spec.order })
            .firstOrNull()
            ?: return fail("STUDIO_DISCOVERY_SEARCH_UNREACHABLE", "Не удалось загрузить ни один известный вариант поиска.")

        val summary = summarize(probes, best)
        if (best.signals.watchPath.isBlank()) {
            return StudioDiscoveryResult(
                success = false,
                finalUrl = input,
                query = probeQuery,
                searchHtml = best.html,
                searchUrl = best.finalUrl.ifBlank { best.requestedUrl },
                searchTemplate = best.spec.template,
                score = best.score,
                summary = summary,
                evidence = evidence(probes, best, null),
                errorCode = "STUDIO_DISCOVERY_NO_VIEW_LINK",
                message = "Поиск доступен, но подтверждённая ссылка на видео не найдена. Измените тестовый запрос.",
            )
        }

        val view = probeView(base, host, best.signals)
        val engine = chooseEngine(best.signals, view)
        val allowedHosts = linkedSetOf(host, "*.$host").apply {
            hostOf(view?.viewUrl.orEmpty())?.let(::add)
            hostOf(view?.iframeUrl.orEmpty())?.let(::add)
            StudioDiscoveryHeuristics.extractMediaHosts(view?.effectiveHtml.orEmpty()).forEach(::add)
        }.filter { it.isNotBlank() }

        val baseDraft = AdapterStudioCore.analyze(input, best.html).getOrElse {
            return fail("STUDIO_ANALYZE_FAILED", it.message ?: "Не удалось построить локальный SNP.")
        }
        val draft = baseDraft.copy(
            engine = engine,
            searchUrl = best.spec.template,
            healthQuery = probeQuery,
            allowedHosts = allowedHosts,
            notes = buildList {
                add("Discovery: ${best.spec.label}, score=${best.score}")
                add("Engine: $engine")
                add("Search: ${best.finalUrl.ifBlank { best.requestedUrl }}")
                if (!view?.viewUrl.isNullOrBlank()) add("View: ${view?.viewUrl}")
                if (!view?.iframeUrl.isNullOrBlank()) add("Iframe: ${view?.iframeUrl}")
            },
        )
        return StudioDiscoveryResult(
            success = true,
            finalUrl = input,
            query = probeQuery,
            draft = draft,
            localJson = AdapterStudioCore.toAdapterJson(draft),
            searchHtml = best.html,
            searchUrl = best.finalUrl.ifBlank { best.requestedUrl },
            searchTemplate = best.spec.template,
            viewHtml = view?.viewHtml.orEmpty(),
            viewUrl = view?.viewUrl.orEmpty(),
            iframeHtml = view?.iframeHtml.orEmpty(),
            iframeUrl = view?.iframeUrl.orEmpty(),
            engine = engine,
            score = best.score,
            summary = summary,
            evidence = evidence(probes, best, view),
            message = "Discovery завершён: подтверждён поиск и определён $engine.",
        )
    }

    private suspend fun probeView(base: String, mainHost: String, search: SearchSignals): ViewProbe? {
        val urls = linkedSetOf<String>()
        fun add(raw: String) {
            if (raw.isBlank()) return
            EngineParsing.absolute(raw, base).takeIf { it.startsWith("https://", true) }?.let(urls::add)
        }
        add(search.watchPath)
        when (search.kind) {
            SiteKind.KVS_VIEW -> if (search.remoteId.isNotBlank()) add("/view/${search.remoteId}/")
            SiteKind.KVS_VIDEOS -> if (search.remoteId.isNotBlank()) add("/videos/${search.remoteId}/")
            SiteKind.NOODLE -> if (search.remoteId.isNotBlank()) add("/watch/${search.remoteId}")
            SiteKind.NOVIDEO -> if (search.remoteId.isNotBlank()) add("/novideo/${search.remoteId}.html")
            SiteKind.UNKNOWN -> Unit
        }
        val trimmed = search.watchPath.trimEnd('/')
        if (trimmed.isNotBlank()) { add(trimmed); add("$trimmed/") }

        var best: ViewProbe? = null
        for (candidate in urls.take(5)) {
            val candidateHost = hostOf(candidate) ?: mainHost
            val net = http.fetchText(
                candidate,
                NetworkPolicy(listOf(mainHost, "*.$mainHost", candidateHost), maxResponseBytes = 8 * 1024 * 1024, maxRedirects = 4),
                SOURCE_KEY,
            )
            if (!net.success) continue
            var current = ViewProbe(viewUrl = net.finalUrl, viewHtml = net.text)
            var effectiveHtml = net.text
            var effectiveUrl = net.finalUrl
            var signals = StudioDiscoveryHeuristics.inspectView(effectiveHtml)
            if (!signals.strong) {
                val iframeRaw = StudioDiscoveryHeuristics.firstIframe(effectiveHtml)
                val iframeUrl = EngineParsing.absolute(iframeRaw, net.finalUrl)
                val iframeHost = hostOf(iframeUrl)
                if (iframeUrl.startsWith("https://", true) && !iframeHost.isNullOrBlank()) {
                    val iframe = http.fetchText(
                        iframeUrl,
                        NetworkPolicy(listOf(mainHost, "*.$mainHost", candidateHost, iframeHost), maxResponseBytes = 8 * 1024 * 1024, maxRedirects = 4),
                        SOURCE_KEY,
                    )
                    if (iframe.success) {
                        current = current.copy(iframeUrl = iframe.finalUrl, iframeHtml = iframe.text)
                        effectiveHtml = iframe.text
                        effectiveUrl = iframe.finalUrl
                        signals = StudioDiscoveryHeuristics.inspectView(effectiveHtml)
                    }
                }
            }
            val scored = current.copy(
                effectiveUrl = effectiveUrl,
                effectiveHtml = effectiveHtml,
                signals = signals,
                score = StudioDiscoveryHeuristics.scoreView(signals, effectiveHtml),
            )
            if (best == null || scored.score > best.score) best = scored
        }
        return best
    }

    private fun chooseEngine(search: SearchSignals, view: ViewProbe?): String = when (search.kind) {
        SiteKind.KVS_VIEW -> "kvs_view"
        SiteKind.KVS_VIDEOS -> "kvs_videos"
        SiteKind.NOODLE -> "noodle_watch_playlist"
        SiteKind.NOVIDEO -> "novideo_embed"
        SiteKind.UNKNOWN -> when {
            view?.signals?.hasPlaylist == true -> "noodle_watch_playlist"
            view?.signals?.hasFlashvars == true -> "kvs_view"
            else -> "declarative_catalog"
        }
    }

    private fun summarize(all: List<SearchProbe>, best: SearchProbe) =
        "Проверено ${all.size} вариантов поиска • выбран ${best.spec.label} • score=${best.score} • engine=${best.signals.kind.wire} • matches=${best.signals.viewCount}/${best.signals.videosCount}/${best.signals.watchCount}"

    private fun evidence(all: List<SearchProbe>, best: SearchProbe, view: ViewProbe?): String = buildString {
        appendLine("DISCOVERY FACTS (confirmed by NoodleMobile; authoritative):")
        appendLine("probe_query=${best.spec.queryEncoding}")
        appendLine("search_candidate=${best.spec.label}")
        appendLine("search_template=${best.spec.template}")
        appendLine("search_url=${best.finalUrl.ifBlank { best.requestedUrl }}")
        appendLine("search_http=${best.status}")
        appendLine("search_score=${best.score}")
        appendLine("detected_kind=${best.signals.kind.wire}")
        appendLine("watch_path=${best.signals.watchPath}")
        appendLine("remote_id=${best.signals.remoteId}")
        appendLine("search_matches=view:${best.signals.viewCount},videos:${best.signals.videosCount},watch:${best.signals.watchCount}")
        if (view != null) {
            appendLine("view_url=${view.viewUrl}")
            appendLine("iframe_url=${view.iframeUrl}")
            appendLine("view_score=${view.score}")
            appendLine("view_signals=flashvars:${view.signals.hasFlashvars},playlist:${view.signals.hasPlaylist},video_url:${view.signals.hasVideoUrl},preview:${view.signals.hasPreview}")
        }
        appendLine("top_candidates:")
        all.sortedByDescending { it.score }.take(8).forEach {
            appendLine("- ${it.spec.label}: loaded=${it.loaded}, http=${it.status}, score=${it.score}, kind=${it.signals.kind.wire}, url=${it.finalUrl.ifBlank { it.requestedUrl }}")
        }
    }

    private fun render(spec: SearchSpec, base: String, query: String): String {
        val encoded = EngineParsing.encodedQuery(query).let { if (spec.plus) it.replace("%20", "+", true) else it }
        return EngineParsing.absolute(spec.template.replace("{query}", encoded, true), base)
    }

    private fun candidates() = listOf(
        SearchSpec(0, "search_q_20", "/search/?q={query}"),
        SearchSpec(1, "search_q_plus", "/search/?q={query}", true),
        SearchSpec(2, "search_path_20", "/search/{query}/"),
        SearchSpec(3, "search_path_plus", "/search/{query}/", true),
        SearchSpec(4, "search_plain_20", "/search/{query}"),
        SearchSpec(5, "search_plain_plus", "/search/{query}", true),
        SearchSpec(6, "query_s_20", "/?s={query}"),
        SearchSpec(7, "query_s_plus", "/?s={query}", true),
        SearchSpec(8, "query_q_20", "/?q={query}"),
        SearchSpec(9, "search_query_20", "/search?q={query}"),
        SearchSpec(10, "video_20", "/video/{query}"),
        SearchSpec(11, "video_plus", "/video/{query}", true),
        SearchSpec(12, "videos_20", "/videos/{query}"),
        SearchSpec(13, "category_direct_20", "/{query}/"),
        SearchSpec(14, "category_direct_plus", "/{query}/", true),
        SearchSpec(15, "category_latest_20", "/{query}/latest/"),
        SearchSpec(16, "category_latest_plus", "/{query}/latest/", true),
        SearchSpec(17, "tag_20", "/tag/{query}/"),
        SearchSpec(18, "tags_20", "/tags/{query}/"),
        SearchSpec(19, "dle_story_20", "/index.php?do=search&subaction=search&story={query}"),
        SearchSpec(20, "dle_story_plus", "/index.php?do=search&subaction=search&story={query}", true),
    )

    private fun normalizeHttps(raw: String): String? = runCatching {
        val u = URI(raw.trim())
        if (!u.scheme.equals("https", true) || u.host.isNullOrBlank()) null else u.toString()
    }.getOrNull()

    private fun originOf(raw: String): String = runCatching {
        val u = URI(raw)
        "https://${u.host}${if (u.port >= 0) ":${u.port}" else ""}"
    }.getOrDefault(raw.trimEnd('/'))

    private fun fallbackQuery(raw: String): String = runCatching {
        URI(raw).path.orEmpty().trim('/').substringAfterLast('/').takeIf { it.length in 3..48 }
    }.getOrNull().orEmpty().ifBlank { "video" }

    private fun hostOf(raw: String): String? = runCatching { URI(raw).host?.lowercase() }.getOrNull()
    private fun fail(code: String, message: String) = StudioDiscoveryResult(false, errorCode = code, message = message)

    private data class SearchSpec(val order: Int, val label: String, val template: String, val plus: Boolean = false) {
        val queryEncoding: String get() = if (plus) "plus" else "percent"
    }
    private data class SearchProbe(
        val spec: SearchSpec,
        val requestedUrl: String,
        val finalUrl: String = "",
        val html: String = "",
        val status: Int,
        val loaded: Boolean,
        val signals: SearchSignals = SearchSignals(),
        val score: Int,
    )
    private data class ViewProbe(
        val viewUrl: String,
        val viewHtml: String,
        val iframeUrl: String = "",
        val iframeHtml: String = "",
        val effectiveUrl: String = viewUrl,
        val effectiveHtml: String = viewHtml,
        val signals: ViewSignals = ViewSignals(),
        val score: Int = 0,
    )

    companion object { private const val SOURCE_KEY = "@adapter-studio-discovery" }
}

internal enum class SiteKind(val wire: String) { UNKNOWN("unknown"), KVS_VIEW("kvs_view"), KVS_VIDEOS("kvs_videos"), NOODLE("noodle_watch_playlist"), NOVIDEO("novideo_embed") }

internal data class SearchSignals(
    val kind: SiteKind = SiteKind.UNKNOWN,
    val watchPath: String = "",
    val remoteId: String = "",
    val viewCount: Int = 0,
    val videosCount: Int = 0,
    val watchCount: Int = 0,
    val hasFlashvars: Boolean = false,
    val hasPlaylist: Boolean = false,
    val hasVideoUrl: Boolean = false,
    val hasPreview: Boolean = false,
)

internal data class ViewSignals(
    val hasFlashvars: Boolean = false,
    val hasPlaylist: Boolean = false,
    val hasVideoUrl: Boolean = false,
    val hasPreview: Boolean = false,
) { val strong: Boolean get() = hasFlashvars || hasPlaylist || hasVideoUrl || hasPreview }

internal object StudioDiscoveryHeuristics {
    private val viewLink = Regex("<a[^>]*href=[\"']((?:https?://[^/\"']+)?/view/(\\d+)/?)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val videosLink = Regex("<a[^>]*href=[\"'](/videos/[^\"']+)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val watchLink = Regex("<a[^>]*href=[\"']/watch/([^\"']+)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val novideoLink = Regex("<a[^>]*href=[\"']((?:https?://[^/\"']+)?(/novideo/([^\"'/?#]+)\\.html))[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val outLink = Regex("/out/\\?ps=([^\"'\\s<>]+)", RegexOption.IGNORE_CASE)

    fun inspectSearch(html: String): SearchSignals {
        val views = viewLink.findAll(html).toList(); val videos = videosLink.findAll(html).toList(); val watches = watchLink.findAll(html).toList(); val novideos = novideoLink.findAll(html).toList(); val outs = outLink.findAll(html).toList()
        var kind = SiteKind.UNKNOWN; var watchPath = ""; var remoteId = ""
        when {
            views.isNotEmpty() -> { kind = SiteKind.KVS_VIEW; watchPath = views.first().groupValues[1]; remoteId = views.first().groupValues[2] }
            videos.isNotEmpty() -> { kind = SiteKind.KVS_VIDEOS; watchPath = videos.firstOrNull { !imageLike(it.groupValues[1]) }?.groupValues?.get(1).orEmpty(); remoteId = watchPath.removePrefix("/videos/").trimEnd('/') }
            watches.isNotEmpty() -> { kind = SiteKind.NOODLE; remoteId = watches.first().groupValues[1]; watchPath = "/watch/$remoteId" }
            novideos.isNotEmpty() -> { kind = SiteKind.NOVIDEO; val m = novideos.first(); watchPath = m.groupValues[2].ifBlank { m.groupValues[1] }; remoteId = m.groupValues[3] }
            outs.isNotEmpty() -> { kind = SiteKind.NOVIDEO; watchPath = decodeOut(outs.first().groupValues[1]); remoteId = Regex("/novideo/([^\"'/?#]+)\\.html", RegexOption.IGNORE_CASE).find(watchPath)?.groupValues?.getOrNull(1).orEmpty() }
        }
        return SearchSignals(kind, watchPath, remoteId, views.size, videos.size, watches.size + novideos.size + outs.size, hasFlashvars(html), hasPlaylist(html), hasVideo(html), hasPreview(html))
    }

    fun scoreSearch(s: SearchSignals, html: String, query: String, url: String): Int {
        var n = 8 + when (s.kind) { SiteKind.KVS_VIEW -> 38; SiteKind.KVS_VIDEOS -> 36; SiteKind.NOODLE -> 34; SiteKind.NOVIDEO -> 33; SiteKind.UNKNOWN -> 0 }
        n += minOf(18, s.viewCount * 2) + minOf(18, s.videosCount * 2) + minOf(18, s.watchCount * 2)
        if (s.watchPath.isNotBlank()) n += 14; if (s.remoteId.isNotBlank()) n += 8; if (s.hasFlashvars) n += 14; if (s.hasPlaylist) n += 12; if (s.hasPreview) n += 8; if (s.hasVideoUrl) n += 8
        if (html.length > 12_000) n += 4; if (url.contains("/search", true)) n += 3
        val needle = query.trim().lowercase().take(48); if (needle.isNotBlank() && html.lowercase().contains(needle)) n += 6
        return n
    }

    fun inspectView(html: String) = ViewSignals(hasFlashvars(html), hasPlaylist(html), hasVideo(html), hasPreview(html))
    fun scoreView(s: ViewSignals, html: String): Int { var n = 0; if (s.hasFlashvars) n += 18; if (s.hasPlaylist) n += 16; if (s.hasVideoUrl) n += 14; if (s.hasPreview) n += 8; if (html.length > 10_000) n += 4; if (s.strong && n < 24) n += 24; return n }
    fun firstIframe(html: String) = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.getOrNull(1).orEmpty()

    fun extractMediaHosts(html: String): Set<String> {
        val out = linkedSetOf<String>()
        Regex("https://[A-Za-z0-9._-]+(?:/[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            if (m.value.contains(".mp4", true) || m.value.contains(".m3u8", true) || m.value.contains(".mpd", true) || m.value.contains("cdn", true)) runCatching { URI(m.value).host?.lowercase() }.getOrNull()?.let(out::add)
        }
        return out.take(8).toSet()
    }

    private fun hasFlashvars(h: String) = Regex("flashvars\\s*=\\s*\\{", RegexOption.IGNORE_CASE).containsMatchIn(h)
    private fun hasPlaylist(h: String) = Regex("window\\.playlist\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(h)
    private fun hasPreview(h: String) = listOf("\\bpreview_url\\s*:\\s*[\"']", "\\bposter\\s*:\\s*[\"']", "og:image", "<video[^>]+poster=").any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(h) }
    private fun hasVideo(h: String) = listOf("\\bvideo_url\\s*:\\s*[\"']", "\\bvideo_alt_url(?:_[a-z0-9]+)?\\s*:\\s*[\"']", "og:video", "<source[^>]+src=", "https://[^\"'<>\\s]+\\.(?:mp4|m3u8|mpd)").any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(h) }
    private fun imageLike(path: String) = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg").any(path.substringBefore('?').lowercase()::endsWith)
    private fun decodeOut(value: String): String = runCatching { val decoded = URLDecoder.decode(value, Charsets.UTF_8.name()); val json = String(Base64.getDecoder().decode(decoded), Charsets.UTF_8).replace("\\/", "/"); Regex("(/novideo/[^\"'\\s<>]+\\.html)", RegexOption.IGNORE_CASE).find(json)?.groupValues?.getOrNull(1).orEmpty() }.getOrDefault("")
}
