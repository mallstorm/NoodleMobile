package pro.noodle.mobile.core.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.TextTrack
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.DeepMediaMiner
import pro.noodle.mobile.core.resolver.DurationText
import pro.noodle.mobile.core.resolver.ResolverPipeline
import pro.noodle.mobile.core.resolver.StreamHints
import pro.noodle.mobile.core.resolver.StreamSelection
import pro.noodle.mobile.core.resolver.StreamSelector
import pro.noodle.mobile.core.resolver.StreamVerdict
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.source.SourceRuntimeDiagnosticsStore
import pro.noodle.mobile.core.web.SandboxedWebExtractor
import java.net.URI

data class PlaybackSession(
    val video: VideoItem,
    val networkPolicy: NetworkPolicy,
    val sourceKey: String,
    /** Page that embeds the player: CDNs with hot-link protection require it on every media request. */
    val referer: String = "",
    /** How the chosen stream was judged: verdict + probe facts, e.g. "full • mp4 12:31 148 МБ". */
    val streamNote: String = "",
    val streamVerdict: String = "",
    val expectedSeconds: Int = 0,
    /** Verification evidence: every extraction variant and what each candidate measured as. */
    val streamDetails: String = "",
)

data class PlaybackLoadResult(
    val success: Boolean,
    val session: PlaybackSession? = null,
    val errorCode: String = "",
    val errorMessage: String = "",
)

class PlaybackRepository(context: Context, private val http: SecureHttpClient = SecureHttpClient()) {
    private val ruleStore = StreamRuleStore(context.applicationContext)
    private val webExtractor = SandboxedWebExtractor(context.applicationContext)
    private val diagnostics = SourceRuntimeDiagnosticsStore(context.applicationContext)

    private val mirrors = pro.noodle.mobile.core.source.MirrorStore(context.applicationContext)

    suspend fun resolve(original: VideoItem, adapter: AdapterDefinition, exhaustive: Boolean = false): PlaybackLoadResult {
        BackgroundGate.enter()
        try { return resolveCore(original, adapter, exhaustive) } finally { BackgroundGate.leave() }
    }

    private suspend fun resolveCore(original: VideoItem, adapter: AdapterDefinition, exhaustive: Boolean): PlaybackLoadResult = withContext(Dispatchers.IO) {
        // Ссылки из избранного/истории со старым доменом переписываем на актуальное зеркало.
        val video = mirrors.rewriteUrl(adapter.id, original.watchUrl).let { if (it == original.watchUrl) original else original.copy(watchUrl = it, playerPageUrl = mirrors.rewriteUrl(adapter.id, original.playerPageUrl)) }
        if (!isStaticEntryAllowed(video.watchUrl, adapter)) {
            diagnostics.recordPlayback(adapter.id, video.watchUrl, "", "entry_blocked", "PLAYBACK_ENTRY_BLOCKED")
            return@withContext PlaybackLoadResult(
                false,
                errorCode = "PLAYBACK_ENTRY_BLOCKED",
                errorMessage = "Страница ролика не прошла исходную SNP/security policy.",
            )
        }

        // Exact public HTTPS hosts discovered while following the resolver chain. We never add
        // wildcard hosts and never allow HTTP/private addresses. This handles rotating CDN/iframe
        // domains without turning the SNP policy into an unrestricted network client.
        val discoveredHosts = linkedSetOf<String>()
        val page = fetchWithSafeDiscoveredRedirects(video.watchUrl, adapter, discoveredHosts)
        var initialText = page.text
        var initialUrl = page.finalUrl.ifBlank { video.watchUrl }
        var usedWeb = false
        var webHtml = ""
        fun rememberHosts(urls: List<String>) {
            urls.forEach { u ->
                if (u.startsWith("https://", true)) runCatching { android.net.Uri.parse(u).host?.lowercase()?.trimEnd('.') }.getOrNull()?.takeIf { it.isNotBlank() }?.let { discoveredHosts += it }
            }
        }

        if (!page.success) {
            if (!adapter.allowJavaScript) {
                diagnostics.recordPlayback(adapter.id, video.watchUrl, "", "page_failed", page.errorCode)
                return@withContext PlaybackLoadResult(false, errorCode = page.errorCode, errorMessage = page.errorMessage)
            }
            val extracted = webExtractor.extract(video.watchUrl, adapter, additionalAllowedHosts = discoveredHosts)
            if (!extracted.success) {
                val code = extracted.errorCode.ifBlank { page.errorCode }
                diagnostics.recordPlayback(adapter.id, video.watchUrl, "", "web_failed", code)
                return@withContext PlaybackLoadResult(
                    false,
                    errorCode = code,
                    errorMessage = extracted.errorMessage.ifBlank { page.errorMessage },
                )
            }
            initialText = extracted.html
            initialUrl = extracted.finalUrl.ifBlank { video.watchUrl }
            usedWeb = true
            webHtml = extracted.html
            rememberHosts(extracted.capturedMedia)
        }

        val fetchedPages = mutableListOf<Pair<String, String>>()
        val fetcher = ResolverFetcher { url ->
            fetchResolverText(url, adapter, discoveredHosts)?.also { body ->
                synchronized(fetchedPages) { if (fetchedPages.size < 4 && !url.contains(".m3u8", true) && !url.contains(".mpd", true)) fetchedPages += url to body }
            }
        }
        var resolved = ResolverPipeline.resolve(initialText, initialUrl, adapter.engine, adapter.viewStrategies, fetcher)
        var safeSources = resolved.sources.filter { admitMediaSource(it, adapter, discoveredHosts) }

        // Static HTML sometimes loads successfully but the real player config appears only after JS.
        if (safeSources.isEmpty() && adapter.allowJavaScript && !usedWeb) {
            val extracted = webExtractor.extract(
                initialUrl,
                adapter,
                additionalAllowedHosts = discoveredHosts,
            )
            if (extracted.success) {
                webHtml = extracted.html
                rememberHosts(extracted.capturedMedia)
                val jsResolved = ResolverPipeline.resolve(
                    extracted.html,
                    extracted.finalUrl.ifBlank { initialUrl },
                    adapter.engine,
                    adapter.viewStrategies,
                    fetcher,
                )
                if (jsResolved.success || jsResolved.sources.isNotEmpty()) resolved = jsResolved
                safeSources = resolved.sources.filter { admitMediaSource(it, adapter, discoveredHosts) }
            }
        }

        if (safeSources.isEmpty()) {
            // Standard engines found nothing: dig through JSON blobs, escaped strings and atob() payloads.
            val mined = DeepMediaMiner.mine(if (webHtml.isBlank() || webHtml == initialText) initialText else initialText + "\n" + webHtml, initialUrl, adapter.streamExtract).filter { admitMediaSource(it, adapter, discoveredHosts) }
            if (mined.isNotEmpty()) {
                resolved = resolved.copy(success = true, sources = mined, errorCode = "", errorMessage = "")
                safeSources = mined
            }
        }

        if (!resolved.success && safeSources.isEmpty()) {
            val code = resolved.errorCode.ifBlank { "PLAYBACK_RESOLVE_FAILED" }
            diagnostics.recordPlayback(adapter.id, video.watchUrl, "", "resolve_failed", code)
            return@withContext PlaybackLoadResult(
                false,
                errorCode = code,
                errorMessage = resolved.errorMessage.ifBlank { "Resolver не смог извлечь воспроизводимый поток." },
            )
        }

        val safeTextTracks = resolved.tracks.filter { admitTextTrack(it, adapter, discoveredHosts) }
        if (safeSources.isEmpty()) {
            val blockedHosts = resolved.sources.mapNotNull { source -> hostOf(source.url) }.distinct().take(6)
            val hostDetails = blockedHosts.joinToString(", ").takeIf { it.isNotBlank() }
                ?.let { " Заблокированные hosts: $it." }.orEmpty()
            diagnostics.recordPlayback(adapter.id, video.watchUrl, "", "blocked_sources", "PLAYBACK_NO_ALLOWED_SOURCE")
            return@withContext PlaybackLoadResult(
                false,
                errorCode = "PLAYBACK_NO_ALLOWED_SOURCE",
                errorMessage = "Resolver нашёл media URL, но поток не прошёл HTTPS/public-host policy.$hostDetails",
            )
        }

        // Rank candidates by what they really are: a measured 40-minute stream beats a 5-second
        // preview, whatever order the page listed them in. Per-site rules learned by Studio apply.
        val expectedSec = DurationText.seconds(video.duration).takeIf { it > 0 } ?: DurationText.fromPage(initialText)
        val userReject = ruleStore.rejectFor(adapter.id)
        val rejectRule = listOf(adapter.streamReject, userReject).filter { it.isNotBlank() }.joinToString("|")
        val hints = StreamHints(adapter.streamPrefer, rejectRule, adapter.streamMinSeconds, adapter.streamExtract)
        val pageBodies = buildList { add(initialUrl to initialText); synchronized(fetchedPages) { addAll(fetchedPages) } }
        var referer = resolved.playerPageUrl.ifBlank { initialUrl }
        var selection = selectStreams(safeSources, adapter, referer, expectedSec, hints, pageBodies, exhaustive)
        if (selection.ranked.isNotEmpty() && selection.ranked.all { it.facts != null && !it.facts.ok && it.facts.status in setOf(401, 403) }) {
            val alt = adapter.baseUrl.trimEnd('/') + "/"
            if (!alt.equals(referer, true)) {
                val retry = selectStreams(safeSources, adapter, alt, expectedSec, hints, pageBodies, exhaustive)
                if (retry.ranked.any { it.facts?.ok == true }) { selection = retry; referer = alt }
            }
        }
        // Mined/extra candidates may live on CDN hosts that were not seen yet: admit them under the same rules.
        safeSources = selection.sources.filter { admitMediaSource(it, adapter, discoveredHosts) }
        if (safeSources.isEmpty()) {
            diagnostics.recordPlayback(adapter.id, video.watchUrl, "", "blocked_sources", "PLAYBACK_NO_ALLOWED_SOURCE")
            return@withContext PlaybackLoadResult(false, errorCode = "PLAYBACK_NO_ALLOWED_SOURCE", errorMessage = "Resolver нашёл media URL, но поток не прошёл HTTPS/public-host policy.")
        }
        val sessionPolicy = policyFor(adapter, discoveredHosts)
        val selected = safeSources.firstOrNull { it.isDefault } ?: safeSources.firstOrNull()
        diagnostics.recordPlayback(
            adapter.id,
            video.watchUrl,
            selected?.url.orEmpty(),
            if (selected != null) "resolved" else "no_source",
            if (selected == null) "PLAYBACK_NO_SOURCE" else "",
        )

        val out = video.copy(
            title = pickTitle(video.title, resolved.title),
            thumbnailUrl = resolved.thumbnailUrl
                .takeIf { it.isBlank() || isCurrentlyAllowed(it, adapter, discoveredHosts) }
                .orEmpty()
                .ifBlank { video.thumbnailUrl },
            playerPageUrl = resolved.playerPageUrl
                .takeIf { it.isBlank() || isCurrentlyAllowed(it, adapter, discoveredHosts) }
                .orEmpty()
                .ifBlank { video.watchUrl },
            sources = safeSources,
            variants = resolved.variants,
            audioTracks = resolved.audioTracks,
            textTracks = safeTextTracks,
        )
        PlaybackLoadResult(true, PlaybackSession(out, sessionPolicy, adapter.id, referer, selection.summary, selection.best.name.lowercase(), expectedSec, selection.details()))
    }

    private suspend fun selectStreams(
        sources: List<MediaSource>,
        adapter: AdapterDefinition,
        referer: String,
        expectedSec: Int,
        hints: StreamHints,
        pages: List<Pair<String, String>>,
        exhaustive: Boolean = false,
    ): StreamSelection {
        // Probing is read-only and public-HTTPS-only (UrlPolicy); candidate CDN hosts are admitted afterwards.
        val client = http.streamingClient(NetworkPolicy(emptyList(), allowCookies = adapter.allowCookies), adapter.id)
        return runCatching {
            kotlinx.coroutines.withTimeoutOrNull(if (exhaustive) SELECT_TIMEOUT_MS + 20_000L else SELECT_TIMEOUT_MS) { StreamSelector(client, referer).select(sources, expectedSec, hints, pages, exhaustive) }
        }.getOrNull() ?: StreamSelection(sources, emptyList(), expectedSec, StreamVerdict.UNKNOWN)
    }

    private suspend fun fetchWithSafeDiscoveredRedirects(
        url: String,
        adapter: AdapterDefinition,
        discoveredHosts: MutableSet<String>,
    ): pro.noodle.mobile.core.network.TextFetchResult {
        var result = http.fetchText(url, policyFor(adapter, discoveredHosts), adapter.id)
        var attempts = 0
        while (!result.success && result.errorCode == "URL_HOST_NOT_ALLOWED" && attempts < MAX_DISCOVERED_HOSTS) {
            if (!discoverPublicHttpsHost(result.finalUrl, adapter, discoveredHosts)) break
            attempts++
            result = http.fetchText(url, policyFor(adapter, discoveredHosts), adapter.id)
        }
        return result
    }

    private fun fetchResolverText(
        url: String,
        adapter: AdapterDefinition,
        discoveredHosts: MutableSet<String>,
    ): String? {
        if (!isCurrentlyAllowed(url, adapter, discoveredHosts) &&
            !discoverPublicHttpsHost(url, adapter, discoveredHosts)
        ) return null

        var attempts = 0
        while (attempts <= MAX_DISCOVERED_HOSTS) {
            val result = http.fetchTextBlocking(url, policyFor(adapter, discoveredHosts), adapter.id)
            if (result.success) return result.text
            if (result.errorCode != "URL_HOST_NOT_ALLOWED") return null
            if (!discoverPublicHttpsHost(result.finalUrl, adapter, discoveredHosts)) return null
            attempts++
        }
        return null
    }

    private fun admitMediaSource(
        source: MediaSource,
        adapter: AdapterDefinition,
        discoveredHosts: MutableSet<String>,
    ): Boolean {
        if (isCurrentlyAllowed(source.url, adapter, discoveredHosts)) return true
        if (!looksLikeMedia(source)) return false
        return discoverPublicHttpsHost(source.url, adapter, discoveredHosts)
    }

    private fun admitTextTrack(
        track: TextTrack,
        adapter: AdapterDefinition,
        discoveredHosts: MutableSet<String>,
    ): Boolean {
        if (isCurrentlyAllowed(track.url, adapter, discoveredHosts)) return true
        val lower = track.url.substringBefore('?').lowercase()
        if (!(lower.endsWith(".vtt") || lower.endsWith(".srt") || lower.endsWith(".ttml") || lower.endsWith(".xml"))) {
            return false
        }
        return discoverPublicHttpsHost(track.url, adapter, discoveredHosts)
    }

    private fun looksLikeMedia(source: MediaSource): Boolean {
        val lower = source.url.substringBefore('?').lowercase()
        val protocol = source.protocol.lowercase()
        val mime = source.mimeType.lowercase()
        return protocol in setOf("hls", "dash", "http", "https", "progressive") ||
            lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".mp4") ||
            lower.endsWith(".webm") || lower.endsWith(".m4v") ||
            mime.startsWith("video/") || mime.contains("mpegurl") || mime.contains("dash+xml")
    }

    private fun discoverPublicHttpsHost(
        url: String,
        adapter: AdapterDefinition,
        discoveredHosts: MutableSet<String>,
    ): Boolean {
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp = false, resolveDns = true)
        if (!decision.allowed) return false
        val host = hostOf(decision.normalizedUrl) ?: return false
        if (adapter.allowedHosts.isEmpty() || UrlPolicy.isHostAllowed(host, adapter.allowedHosts)) return true
        if (host in discoveredHosts) return true
        if (discoveredHosts.size >= MAX_DISCOVERED_HOSTS) return false
        discoveredHosts += host
        return true
    }

    private fun isStaticEntryAllowed(url: String, adapter: AdapterDefinition): Boolean {
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp = false, resolveDns = true)
        if (!decision.allowed) return false
        val host = hostOf(decision.normalizedUrl) ?: return false
        return adapter.allowedHosts.isEmpty() || UrlPolicy.isHostAllowed(host, adapter.allowedHosts)
    }

    /**
     * Название карточки из поиска надёжнее, чем <title> страницы просмотра («Videos», «Watch», имя сайта).
     * Название со страницы берём только если у карточки его нет или оно похоже на слаг из URL.
     */
    private fun pickTitle(card: String, page: String): String {
        val c = card.trim()
        val p = page.trim()
        if (c.isBlank()) return p
        if (p.isBlank()) return c
        val generic = setOf("video", "videos", "watch", "player", "видео", "смотреть", "home", "главная")
        if (p.lowercase() in generic || p.length < 4) return c
        val slugLike = !c.contains(' ') && (c.contains('-') || c.contains('_'))
        return if (slugLike) p else c
    }

    private fun isCurrentlyAllowed(
        url: String,
        adapter: AdapterDefinition,
        discoveredHosts: Set<String>,
    ): Boolean {
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp = false, resolveDns = false)
        if (!decision.allowed) return false
        val host = hostOf(decision.normalizedUrl) ?: return false
        return adapter.allowedHosts.isEmpty() ||
            UrlPolicy.isHostAllowed(host, adapter.allowedHosts) ||
            host in discoveredHosts
    }

    private fun policyFor(adapter: AdapterDefinition, discoveredHosts: Set<String>): NetworkPolicy =
        NetworkPolicy(
            allowedHosts = (adapter.allowedHosts + discoveredHosts).distinct(),
            allowCookies = adapter.allowCookies,
            maxResponseBytes = 5 * 1024 * 1024,
        )

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val MAX_DISCOVERED_HOSTS = 8
        private const val SELECT_TIMEOUT_MS = 35_000L
    }
}
