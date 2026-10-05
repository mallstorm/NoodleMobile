package pro.noodle.mobile.core.studio

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import java.net.URI

/**
 * Second deterministic HTTP layer for video CMS families outside the original Delphi/KVS route
 * set. It fingerprints the landing/search HTML, prioritizes family-specific routes, then probes a
 * conservative global route set so an unknown skin can still be recognized without AI.
 */
internal class StudioKnownRouteDiscovery(private val http: SecureHttpClient) {
    suspend fun discover(
        url: String,
        queryOverride: String = "",
        baseline: StudioDiscoveryResult? = null,
    ): StudioDiscoveryResult = coroutineScope {
        val input = normalizeHttps(url)
            ?: return@coroutineScope baseline ?: failure("STUDIO_KNOWN_ROUTE_URL_INVALID", "Некорректный HTTPS URL.")
        val host = URI(input).host.lowercase()
        val base = originOf(input)
        val query = queryOverride.trim().ifBlank { baseline?.query.orEmpty().ifBlank { "video" } }
        val policy = NetworkPolicy(listOf(host, "*.$host"), maxResponseBytes = MAX_BYTES, maxRedirects = 4)

        val landing = withTimeoutOrNull(LANDING_TIMEOUT_MS) {
            http.fetchText(input, policy, SOURCE_KEY)
        }
        val fingerprintHtml = buildString {
            if (landing?.success == true) append(landing.text)
            if (!baseline?.searchHtml.isNullOrBlank()) {
                append('\n')
                append(baseline?.searchHtml)
            }
        }
        val matches = StudioEngineKnowledge.detect(fingerprintHtml, input)
        val preferred = StudioEngineKnowledge.searchTemplates(matches)
        // Important: a large knowledge catalogue must not turn every unknown site into hundreds of
        // blind requests. Only profiles explicitly marked as globally safe contribute fallback routes;
        // all other routes become active as soon as their fingerprint is observed.
        val global = StudioEngineKnowledge.globalSearchTemplates()
        val baselineTemplate = baseline?.searchTemplate.orEmpty()
        val templates = (preferred + global)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("{query}") }
            .filterNot { it.equals(baselineTemplate, true) }
            .distinct()
            .take(MAX_ROUTE_PROBES)
            .toList()
        if (templates.isEmpty()) return@coroutineScope baseline ?: failure(
            "STUDIO_KNOWN_ROUTE_EMPTY",
            "Для обнаруженного семейства нет дополнительных маршрутов поиска.",
        )

        val preferredSet = preferred.map { it.lowercase() }.toSet()
        val gate = Semaphore(PARALLELISM)
        val probes = templates.mapIndexed { index, template ->
            async {
                gate.withPermit {
                    val target = render(template, base, query)
                    val net = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        http.fetchText(target, policy, SOURCE_KEY)
                    }
                    if (net == null || !net.success) {
                        RouteProbe(
                            index = index,
                            template = template,
                            url = target,
                            status = net?.statusCode ?: 0,
                            error = net?.errorCode ?: "timeout",
                        )
                    } else {
                        val signals = StudioDiscoveryHeuristics.inspectSearch(net.text)
                        val baseScore = StudioDiscoveryHeuristics.scoreSearch(signals, net.text, query, net.finalUrl)
                        RouteProbe(
                            index = index,
                            template = template,
                            url = net.finalUrl.ifBlank { target },
                            html = net.text,
                            status = net.statusCode,
                            signals = signals,
                            score = baseScore + if (template.lowercase() in preferredSet) PREFERRED_ROUTE_BONUS else 0,
                        )
                    }
                }
            }
        }.awaitAll()

        val best = probes
            .filter { it.html.isNotBlank() && it.signals.watchPath.isNotBlank() }
            .sortedWith(compareByDescending<RouteProbe> { it.score }.thenBy { it.index })
            .firstOrNull()
            ?: return@coroutineScope (baseline ?: failure(
                "STUDIO_KNOWN_ROUTE_NO_RESULTS",
                "Расширенный каталог CMS-маршрутов не подтвердил карточки видео.",
            )).copy(
                evidence = baseline?.evidence.orEmpty() +
                    "\nKNOWN_ROUTE_DISCOVERY: checked=${templates.size}; no confirmed video route; profiles=${StudioEngineKnowledge.summary(matches)}; ${StudioEngineKnowledge.catalogueSummary()}",
            )

        val viewUrl = EngineParsing.absolute(best.signals.watchPath, best.url.ifBlank { base })
        val viewHost = hostOf(viewUrl)
        val view = if (viewUrl.startsWith("https://", true) && !viewHost.isNullOrBlank()) {
            withTimeoutOrNull(VIEW_TIMEOUT_MS) {
                http.fetchText(
                    viewUrl,
                    NetworkPolicy(listOf(host, "*.$host", viewHost), maxResponseBytes = MAX_VIEW_BYTES, maxRedirects = 4),
                    SOURCE_KEY,
                )
            }
        } else null
        val viewHtml = view?.text.orEmpty()
        val viewFinal = view?.finalUrl.orEmpty().ifBlank { viewUrl }
        val allMatches = (matches + StudioEngineKnowledge.detect(viewHtml, viewFinal))
            .distinctBy { it.profile.id }
            .sortedByDescending { it.hits }
        val engine = when (best.signals.kind) {
            SiteKind.KVS_VIEW -> "kvs_view"
            SiteKind.KVS_VIDEOS -> "kvs_videos"
            SiteKind.NOODLE -> "noodle_watch_playlist"
            SiteKind.NOVIDEO -> "novideo_embed"
            SiteKind.UNKNOWN -> "declarative_catalog"
        }
        val allowedHosts = linkedSetOf(host, "*.$host").apply {
            viewHost?.let { add(it) }
            StudioDiscoveryHeuristics.firstIframe(viewHtml).takeIf { it.isNotBlank() }
                ?.let { EngineParsing.absolute(it, viewFinal) }
                ?.let(::hostOf)
                ?.let { add(it) }
            StudioDiscoveryHeuristics.extractMediaHosts(viewHtml).forEach { add(it) }
        }.filter { it.isNotBlank() }

        val baseDraft = AdapterStudioCore.analyze(input, best.html).getOrElse {
            return@coroutineScope baseline ?: failure("STUDIO_KNOWN_ROUTE_ANALYZE", it.message ?: "Не удалось построить SNP.")
        }
        val draft = baseDraft.copy(
            engine = engine,
            searchUrl = best.template,
            healthQuery = query,
            allowedHosts = allowedHosts,
            allowJavaScript = baseDraft.allowJavaScript || allMatches.any { it.profile.requiresJavaScript },
            allowCookies = baseDraft.allowCookies || allMatches.any { it.profile.allowCookies },
            notes = baseDraft.notes + buildList {
                add("Expanded CMS route: ${best.template} • score=${best.score}")
                if (allMatches.isNotEmpty()) add("Fingerprints: ${StudioEngineKnowledge.summary(allMatches)}")
                val strategies = StudioEngineKnowledge.playbackStrategies(allMatches)
                if (strategies.isNotEmpty()) add("Playback strategies: ${strategies.joinToString()}")
                if (viewFinal.isNotBlank()) add("View: $viewFinal")
                add("Knowledge catalogue: ${StudioEngineKnowledge.catalogueSummary()}")
            },
        )
        val summary = buildString {
            append("Expanded routes: checked=${templates.size} • selected=${best.template} • score=${best.score}")
            if (allMatches.isNotEmpty()) append(" • ${StudioEngineKnowledge.summary(allMatches)}")
        }
        val evidence = buildString {
            if (!baseline?.evidence.isNullOrBlank()) appendLine(baseline?.evidence)
            appendLine("KNOWN_ROUTE_DISCOVERY FACTS:")
            appendLine("catalogue=${StudioEngineKnowledge.catalogueSummary()}")
            appendLine("profiles=${StudioEngineKnowledge.summary(allMatches)}")
            appendLine("routes_checked=${templates.size}")
            appendLine("search_template=${best.template}")
            appendLine("search_url=${best.url}")
            appendLine("search_http=${best.status}")
            appendLine("search_score=${best.score}")
            appendLine("detected_kind=${best.signals.kind.wire}")
            appendLine("watch_path=${best.signals.watchPath}")
            appendLine("view_url=$viewFinal")
            appendLine("preferred_playback=${StudioEngineKnowledge.playbackStrategies(allMatches).joinToString(",")}")
        }
        StudioDiscoveryResult(
            success = true,
            finalUrl = input,
            query = query,
            draft = draft,
            localJson = AdapterStudioCore.toAdapterJson(draft),
            searchHtml = best.html,
            searchUrl = best.url,
            searchTemplate = best.template,
            viewHtml = viewHtml,
            viewUrl = viewFinal,
            engine = engine,
            score = best.score,
            summary = summary,
            evidence = evidence,
            message = "Расширенный локальный каталог движков подтвердил поиск и страницу видео.",
        )
    }

    private fun render(template: String, base: String, query: String): String = EngineParsing.absolute(
        template
            .replace("{query}", EngineParsing.encodedQuery(query), true)
            .replace("{page}", "1", true),
        base,
    )

    private fun normalizeHttps(raw: String): String? = runCatching {
        URI(raw.trim()).takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() }?.toString()
    }.getOrNull()

    private fun originOf(raw: String): String = runCatching {
        val u = URI(raw)
        "https://${u.host}${if (u.port >= 0) ":${u.port}" else ""}"
    }.getOrDefault(raw.trimEnd('/'))

    private fun hostOf(raw: String): String? = runCatching { URI(raw).host?.lowercase()?.trimEnd('.') }.getOrNull()

    private fun failure(code: String, message: String) = StudioDiscoveryResult(false, errorCode = code, message = message)

    private data class RouteProbe(
        val index: Int,
        val template: String,
        val url: String,
        val html: String = "",
        val status: Int = 0,
        val signals: SearchSignals = SearchSignals(),
        val score: Int = -1000,
        val error: String = "",
    )

    companion object {
        private const val SOURCE_KEY = "@studio-known-routes"
        private const val PARALLELISM = 6
        private const val MAX_ROUTE_PROBES = 64
        private const val PREFERRED_ROUTE_BONUS = 24
        private const val LANDING_TIMEOUT_MS = 8_000L
        private const val PROBE_TIMEOUT_MS = 8_000L
        private const val VIEW_TIMEOUT_MS = 9_000L
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_VIEW_BYTES = 6 * 1024 * 1024
    }
}
