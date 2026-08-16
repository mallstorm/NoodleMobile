package pro.noodle.mobile.core.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.ResolverPipeline
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.web.SandboxedWebExtractor
import java.net.URI

data class PlaybackSession(
    val video: VideoItem,
    val networkPolicy: NetworkPolicy,
    val sourceKey: String,
)

data class PlaybackLoadResult(
    val success: Boolean,
    val session: PlaybackSession? = null,
    val errorCode: String = "",
    val errorMessage: String = "",
)

class PlaybackRepository(context: Context, private val http: SecureHttpClient = SecureHttpClient()) {
    private val webExtractor = SandboxedWebExtractor(context.applicationContext)

    suspend fun resolve(video: VideoItem, adapter: AdapterDefinition): PlaybackLoadResult = withContext(Dispatchers.IO) {
        val policy = NetworkPolicy(adapter.allowedHosts, allowCookies = adapter.allowCookies, maxResponseBytes = 5 * 1024 * 1024)
        val page = http.fetchText(video.watchUrl, policy, adapter.id)
        var initialText = page.text
        var initialUrl = page.finalUrl.ifBlank { video.watchUrl }
        var usedWeb = false

        if (!page.success) {
            if (!adapter.allowJavaScript) {
                return@withContext PlaybackLoadResult(false, errorCode = page.errorCode, errorMessage = page.errorMessage)
            }
            val extracted = webExtractor.extract(video.watchUrl, adapter)
            if (!extracted.success) {
                return@withContext PlaybackLoadResult(
                    false,
                    errorCode = extracted.errorCode.ifBlank { page.errorCode },
                    errorMessage = extracted.errorMessage.ifBlank { page.errorMessage },
                )
            }
            initialText = extracted.html
            initialUrl = extracted.finalUrl.ifBlank { video.watchUrl }
            usedWeb = true
        }

        val fetcher = ResolverFetcher { url ->
            if (!isAllowed(url, adapter)) null
            else http.fetchTextBlocking(url, policy, adapter.id).takeIf { it.success }?.text
        }
        var resolved = ResolverPipeline.resolve(initialText, initialUrl, adapter.engine, adapter.viewStrategies, fetcher)
        var safeSources = resolved.sources.filter { isAllowed(it.url, adapter) }

        // Static HTML sometimes loads successfully but the real player config appears only after JS.
        if (safeSources.isEmpty() && adapter.allowJavaScript && !usedWeb) {
            val extracted = webExtractor.extract(initialUrl, adapter)
            if (extracted.success) {
                val jsResolved = ResolverPipeline.resolve(
                    extracted.html,
                    extracted.finalUrl.ifBlank { initialUrl },
                    adapter.engine,
                    adapter.viewStrategies,
                    fetcher,
                )
                if (jsResolved.success || jsResolved.sources.isNotEmpty()) resolved = jsResolved
                safeSources = resolved.sources.filter { isAllowed(it.url, adapter) }
            }
        }

        if (!resolved.success && safeSources.isEmpty()) {
            return@withContext PlaybackLoadResult(false, errorCode = resolved.errorCode, errorMessage = resolved.errorMessage)
        }

        // Resolver output is untrusted remote data. Validate every URL before it reaches Media3.
        val safeTextTracks = resolved.tracks.filter { isAllowed(it.url, adapter) }
        if (safeSources.isEmpty()) {
            val blockedHosts = resolved.sources.mapNotNull { source ->
                runCatching { URI(source.url).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
            }.distinct().take(6)
            val hostDetails = blockedHosts.joinToString(", ").takeIf { it.isNotBlank() }?.let { " Заблокированные hosts: $it." }.orEmpty()
            return@withContext PlaybackLoadResult(
                false,
                errorCode = "PLAYBACK_NO_ALLOWED_SOURCE",
                errorMessage = "Источник ${adapter.id}: Resolver нашёл media URL, но ни один из них не прошёл SNP host/security policy.$hostDetails",
            )
        }

        val out = video.copy(
            title = resolved.title.ifBlank { video.title },
            thumbnailUrl = resolved.thumbnailUrl.takeIf { it.isBlank() || isAllowed(it, adapter) }.orEmpty().ifBlank { video.thumbnailUrl },
            playerPageUrl = resolved.playerPageUrl.takeIf { it.isBlank() || isAllowed(it, adapter) }.orEmpty().ifBlank { video.watchUrl },
            sources = safeSources,
            variants = resolved.variants,
            audioTracks = resolved.audioTracks,
            textTracks = safeTextTracks,
        )
        PlaybackLoadResult(true, PlaybackSession(out, policy, adapter.id))
    }

    private fun isAllowed(url: String, adapter: AdapterDefinition): Boolean {
        if (url.isBlank()) return false
        val decision = UrlPolicy.evaluateNavigation(url, allowHttp = false, resolveDns = false)
        if (!decision.allowed) return false
        val host = runCatching { URI(decision.normalizedUrl).host.orEmpty() }.getOrDefault("")
        return adapter.allowedHosts.isEmpty() || UrlPolicy.isHostAllowed(host, adapter.allowedHosts)
    }
}
