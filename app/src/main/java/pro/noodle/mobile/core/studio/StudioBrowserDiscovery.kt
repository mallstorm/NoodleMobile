package pro.noodle.mobile.core.studio

import android.content.Context
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.web.SandboxedWebExtractor
import java.net.URI

/**
 * Browser-assisted second stage for Studio. Deterministic HTTP discovery remains first because it
 * is faster and easier to audit. When it found a plausible search route but could not see dynamic
 * cards/player data, this stage renders that exact confirmed route in the same sandboxed WebView
 * used by playback, then re-runs the Noodle heuristics over the post-JS DOM.
 */
internal class StudioBrowserDiscovery(context: Context) {
    private val web = SandboxedWebExtractor(context.applicationContext)

    suspend fun recover(
        requestedUrl: String,
        deterministic: StudioDiscoveryResult,
        queryOverride: String = "",
    ): StudioDiscoveryResult {
        val searchUrl = deterministic.searchUrl.trim()
        val searchTemplate = deterministic.searchTemplate.trim()
        if (searchUrl.isBlank() || searchTemplate.isBlank()) return deterministic
        val mainHost = hostOf(requestedUrl) ?: return deterministic
        val base = originOf(requestedUrl)
        val query = queryOverride.trim().ifBlank { deterministic.query.ifBlank { "video" } }
        val allowed = linkedSetOf(mainHost, "*.$mainHost")
        hostOf(searchUrl)?.let { allowed += it }

        var adapter = temporaryAdapter(base, allowed)
        val renderedSearch = web.extract(
            searchUrl,
            adapter,
            timeoutMs = SEARCH_TIMEOUT_MS,
            additionalAllowedHosts = allowed,
        )
        if (!renderedSearch.success || renderedSearch.html.isBlank()) {
            return deterministic.copy(
                evidence = deterministic.evidence + "\nBROWSER_DISCOVERY: search render failed: ${renderedSearch.errorCode}: ${renderedSearch.errorMessage}",
            )
        }

        val searchSignals = StudioDiscoveryHeuristics.inspectSearch(renderedSearch.html)
        if (searchSignals.watchPath.isBlank()) {
            return deterministic.copy(
                searchHtml = renderedSearch.html,
                evidence = deterministic.evidence + "\nBROWSER_DISCOVERY: rendered search DOM, but no video link was confirmed.",
            )
        }

        val viewUrl = EngineParsing.absolute(searchSignals.watchPath, renderedSearch.finalUrl.ifBlank { base })
        hostOf(viewUrl)?.let { allowed += it }
        adapter = temporaryAdapter(base, allowed)
        val renderedView = web.extract(
            viewUrl,
            adapter,
            timeoutMs = VIEW_TIMEOUT_MS,
            additionalAllowedHosts = allowed,
        )
        val viewHtml = renderedView.html
        val finalViewUrl = renderedView.finalUrl.ifBlank { viewUrl }
        var iframeUrl = ""
        var iframeHtml = ""
        var effectiveHtml = viewHtml

        if (renderedView.success && !StudioDiscoveryHeuristics.inspectView(viewHtml).strong) {
            val iframeRaw = StudioDiscoveryHeuristics.firstIframe(viewHtml)
            val candidate = EngineParsing.absolute(iframeRaw, finalViewUrl)
            val iframeHost = hostOf(candidate)
            if (candidate.startsWith("https://", true) && !iframeHost.isNullOrBlank()) {
                allowed += iframeHost
                adapter = temporaryAdapter(base, allowed)
                val iframe = web.extract(
                    candidate,
                    adapter,
                    timeoutMs = VIEW_TIMEOUT_MS,
                    additionalAllowedHosts = allowed,
                )
                if (iframe.success) {
                    iframeUrl = iframe.finalUrl.ifBlank { candidate }
                    iframeHtml = iframe.html
                    effectiveHtml = iframeHtml
                }
            }
        }

        val browserMatches = StudioEngineKnowledge.detect(
            renderedSearch.html + "\n" + viewHtml + "\n" + effectiveHtml,
            finalViewUrl,
        )
        val preferredStrategies = StudioEngineKnowledge.playbackStrategies(browserMatches)
        val mediaHosts = StudioDiscoveryHeuristics.extractMediaHosts(effectiveHtml)
        allowed += mediaHosts
        val engine = when (searchSignals.kind) {
            SiteKind.KVS_VIEW -> "kvs_view"
            SiteKind.KVS_VIDEOS -> "kvs_videos"
            SiteKind.NOODLE -> "noodle_watch_playlist"
            SiteKind.NOVIDEO -> "novideo_embed"
            SiteKind.UNKNOWN -> deterministic.engine.ifBlank { "declarative_catalog" }
        }
        val baseDraft = AdapterStudioCore.analyze(requestedUrl, renderedSearch.html).getOrNull() ?: return deterministic
        val draft = baseDraft.copy(
            engine = engine,
            searchUrl = searchTemplate,
            healthQuery = query,
            allowedHosts = allowed.filter { it.isNotBlank() },
            allowJavaScript = true,
            allowCookies = baseDraft.allowCookies || browserMatches.any { it.profile.allowCookies },
            notes = baseDraft.notes + buildList {
                add("Browser Discovery: post-JS search DOM confirmed")
                add("Search: ${renderedSearch.finalUrl.ifBlank { searchUrl }}")
                add("View: $finalViewUrl")
                add("Iframe: ${iframeUrl.ifBlank { "—" }}")
                if (browserMatches.isNotEmpty()) add("Browser fingerprints: ${StudioEngineKnowledge.summary(browserMatches)}")
                if (preferredStrategies.isNotEmpty()) add("Browser playback strategies: ${preferredStrategies.joinToString()}")
            },
        )
        val viewSignals = StudioDiscoveryHeuristics.inspectView(effectiveHtml)
        val viewScore = StudioDiscoveryHeuristics.scoreView(viewSignals, effectiveHtml)
        val summary = buildString {
            append("${deterministic.summary} • WebView DOM confirmed • engine=$engine • viewScore=$viewScore")
            if (browserMatches.isNotEmpty()) append(" • ${StudioEngineKnowledge.summary(browserMatches)}")
        }
        val evidence = buildString {
            append(deterministic.evidence)
            appendLine()
            appendLine("BROWSER_DISCOVERY FACTS:")
            appendLine("catalogue=${StudioEngineKnowledge.catalogueSummary()}")
            appendLine("search_url=${renderedSearch.finalUrl.ifBlank { searchUrl }}")
            appendLine("search_matches=${searchSignals.totalRecognized}")
            appendLine("detected_kind=${searchSignals.kind.wire}")
            appendLine("view_url=$finalViewUrl")
            appendLine("iframe_url=$iframeUrl")
            appendLine("view_score=$viewScore")
            appendLine("dynamic_js=true")
            appendLine("profiles=${StudioEngineKnowledge.summary(browserMatches)}")
            appendLine("playback_strategies=${preferredStrategies.joinToString(",")}")
            appendLine("media_hosts=${mediaHosts.joinToString(",")}")
        }
        return StudioDiscoveryResult(
            success = true,
            finalUrl = requestedUrl,
            query = query,
            draft = draft,
            localJson = AdapterStudioCore.toAdapterJson(draft),
            searchHtml = renderedSearch.html,
            searchUrl = renderedSearch.finalUrl.ifBlank { searchUrl },
            searchTemplate = searchTemplate,
            viewHtml = viewHtml,
            viewUrl = finalViewUrl,
            iframeHtml = iframeHtml,
            iframeUrl = iframeUrl,
            engine = engine,
            score = deterministic.score + viewScore,
            summary = summary,
            evidence = evidence,
            message = "Browser Discovery подтвердил динамические маршруты после выполнения JavaScript.",
        )
    }

    private fun temporaryAdapter(baseUrl: String, allowed: Set<String>) = AdapterDefinition(
        schemaVersion = 3,
        id = "@studio-browser-discovery",
        name = "Studio Browser Discovery",
        baseUrl = baseUrl,
        engine = "declarative_catalog",
        enabled = false,
        allowedHosts = allowed.toList(),
        allowJavaScript = true,
        allowCookies = true,
        viewStrategies = listOf("primary", "jwplayer", "videojs", "player_config", "generic_html5", "iframe", "hls", "dash"),
    )

    private fun hostOf(raw: String): String? = runCatching { URI(raw).host?.lowercase()?.trimEnd('.') }.getOrNull()
    private fun originOf(raw: String): String = runCatching {
        val u = URI(raw)
        "https://${u.host}${if (u.port >= 0) ":${u.port}" else ""}"
    }.getOrDefault(raw.trimEnd('/'))

    companion object {
        private const val SEARCH_TIMEOUT_MS = 15_000L
        private const val VIEW_TIMEOUT_MS = 15_000L
    }
}
