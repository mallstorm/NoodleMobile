package pro.noodle.mobile.core.search

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import pro.noodle.mobile.core.adapter.AdapterDefinition
import pro.noodle.mobile.core.engine.impl.AutoCards
import pro.noodle.mobile.core.engine.runtime.SearchUrls
import pro.noodle.mobile.core.engine.runtime.searchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.settings.SourceOverridesRepository
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.source.SourceRuntimeDiagnosticsStore
import java.net.URI

data class SourceSearchError(val sourceId: String, val sourceName: String, val errorCode: String, val message: String)
data class SearchBatch(val items: List<VideoItem>, val errors: List<SourceSearchError>)

class BundledSearchRepository(
    context: Context,
    private val http: SecureHttpClient = SecureHttpClient(),
    private val parallelism: Int = 4,
) {
    private val runtimeRepo = RuntimeAdapterRepository(context.applicationContext)
    private val overrides = SourceOverridesRepository(context.applicationContext)
    private val diagnostics = SourceRuntimeDiagnosticsStore(context.applicationContext)

    // Shared by every search call, including pagination. Keep the socket/parser fan-out modest on
    // memory-sensitive Android devices rather than allowing page bursts to multiply allocations.
    private val networkSemaphore = Semaphore(parallelism.coerceIn(1, 4))

    /**
     * RuntimeAdapterRepository verifies package integrity by hashing every file. adapterFor() is
     * called repeatedly while ranking cards and loading thumbnails, so doing a fresh listAll() for
     * each card turns result rendering into a disk/SHA storm. Refresh once at search start and use
     * the verified definitions as an in-memory snapshot for the rest of that result generation.
     */
    @Volatile
    private var adapterSnapshot: List<AdapterDefinition> = emptyList()

    private fun allAdapters(): List<AdapterDefinition> {
        val cached = adapterSnapshot
        if (cached.isNotEmpty()) return cached
        return runtimeRepo.listAll().map { it.definition }.also { adapterSnapshot = it }
    }

    private suspend fun activeAdapters(): List<AdapterDefinition> {
        val disabled = overrides.disabledSourceIds.first()
        val forced = overrides.forceEnabledSourceIds.first()
        val solo = overrides.soloSourceId.first()
        val runtime = runtimeRepo.listAll()
        adapterSnapshot = runtime.map { it.definition }
        return runtime
            .filter { it.compatibility.compatible && it.packageInfo.valid }
            .map { it.definition }
            .filter { d ->
                val id = d.id.lowercase()
                val enabled = (d.enabled || id in forced) && id !in disabled
                enabled && (solo.isBlank() || id == solo)
            }
    }

    suspend fun search(query: String, page: Int = -1, maxPerSource: Int = 20): SearchBatch = coroutineScope {
        if (query.isBlank()) return@coroutineScope SearchBatch(emptyList(), emptyList())
        val adapters = activeAdapters()
        directUrlResult(query, adapters)?.let { return@coroutineScope it }
        val parts = adapters.map { adapter ->
            async {
                networkSemaphore.withPermit {
                    try {
                        withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                            searchOne(adapter, query, page, maxPerSource)
                        } ?: (emptyList<VideoItem>() to SourceSearchError(
                            adapter.id,
                            adapter.name,
                            "SEARCH_TIMEOUT",
                            "Источник не ответил за ${SOURCE_SEARCH_TIMEOUT_MS / 1000} с.",
                        ))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        emptyList<VideoItem>() to SourceSearchError(
                            adapter.id,
                            adapter.name,
                            "SEARCH_SOURCE_EXCEPTION",
                            e.message ?: e.javaClass.simpleName,
                        )
                    }
                }
            }
        }.awaitAll()
        SearchBatch(
            parts.flatMap { it.first }
                .distinctBy { "${it.sourceBaseUrl}|${it.remoteId}|${it.watchUrl}".lowercase() },
            parts.mapNotNull { it.second },
        )
    }

    /** Fast watch-page path: load related cards only from the current SNP first. */
    suspend fun searchSameSource(
        video: VideoItem,
        query: String,
        page: Int = -1,
        maxPerSource: Int = 8,
    ): SearchBatch {
        if (query.isBlank()) return SearchBatch(emptyList(), emptyList())
        val adapter = adapterFor(video)
            ?: return SearchBatch(emptyList(), listOf(SourceSearchError(
                "related",
                video.sourceName,
                "RELATED_SOURCE_NOT_FOUND",
                "SNP текущего источника не найден.",
            )))
        val result = try {
            networkSemaphore.withPermit {
                withTimeoutOrNull(RELATED_SEARCH_TIMEOUT_MS) {
                    searchOne(adapter, query, page, maxPerSource.coerceIn(4, 12))
                }
            } ?: (emptyList<VideoItem>() to SourceSearchError(
                adapter.id,
                adapter.name,
                "RELATED_TIMEOUT",
                "Похожие видео загружались слишком долго.",
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            emptyList<VideoItem>() to SourceSearchError(
                adapter.id,
                adapter.name,
                "RELATED_SOURCE_EXCEPTION",
                e.message ?: e.javaClass.simpleName,
            )
        }
        return SearchBatch(result.first, listOfNotNull(result.second))
    }


    /** Fetch related watch-page cards through the current source's validated parser/host policy. */
    suspend fun relatedFromWatchPage(video: VideoItem, maxPerSource: Int = 12): SearchBatch {
        val adapter = adapterFor(video) ?: return SearchBatch(emptyList(), emptyList())
        val watch = normalizeAllowed(video.watchUrl, adapter) ?: return SearchBatch(emptyList(), emptyList())
        val net = try {
            withTimeoutOrNull(RELATED_SEARCH_TIMEOUT_MS) {
                networkSemaphore.withPermit {
                    http.fetchText(watch, NetworkPolicy(adapter.allowedHosts, allowCookies = adapter.allowCookies), adapter.id)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return SearchBatch(emptyList(), emptyList())
        if (!net.success) return SearchBatch(emptyList(), emptyList())

        val engine = RuntimeEngineRegistry.find(adapter.engine) ?: return SearchBatch(emptyList(), emptyList())
        val finalUrl = net.finalUrl.ifBlank { watch }
        val finalBase = originOf(finalUrl, adapter.baseUrl)
        val maximum = maxPerSource.coerceIn(4, 20)
        val parsed = runCatching {
            val request = adapter.searchRequest("", -1, maximum)
            val inferred = engine.parseSearch(net.text, request.copy(baseUrl = finalBase))
            val learned = adapter.linkPattern.takeIf { it.isNotBlank() }
                ?.let { Regex(it, RegexOption.IGNORE_CASE) }
                ?.let { AutoCards.extract(net.text, finalUrl, it, maximum) }
                .orEmpty()
            learned + inferred
        }.getOrDefault(emptyList())

        val items = parsed.mapNotNull { card ->
            val url = normalizeAllowed(card.watchPath, adapter, baseUrl = finalBase) ?: return@mapNotNull null
            if (url.equals(watch, true)) return@mapNotNull null
            val thumb = normalizeAllowed(card.thumbnailUrl, adapter, allowBlank = true, baseUrl = finalBase).orEmpty()
            val remote = card.remoteId.ifBlank { url }
            VideoItem(
                id = adapter.id + "|" + remote,
                remoteId = card.remoteId,
                watchUrl = url,
                title = card.title.ifBlank { remote.substringAfterLast('/') },
                thumbnailUrl = thumb,
                duration = card.durationText,
                views = card.viewsText,
                sourceName = adapter.name,
                sourceBaseUrl = adapter.baseUrl,
                sourceEngine = adapter.engine,
                playerPageUrl = url,
                favoriteKey = adapter.id + "|" + remote,
            )
        }.distinctBy { it.watchUrl.lowercase() }.take(maximum)
        return SearchBatch(items, emptyList())
    }

    private fun directUrlResult(query: String, adapters: List<AdapterDefinition>): SearchBatch? {
        val raw = query.trim()
        if (!raw.startsWith("https://", true) && !raw.startsWith("http://", true)) return null
        val decision = UrlPolicy.evaluateNavigation(raw, allowHttp = false, resolveDns = false)
        if (!decision.allowed) return SearchBatch(
            emptyList(),
            listOf(SourceSearchError("direct-url", "Прямая ссылка", decision.errorCode, decision.reason)),
        )
        val host = runCatching { URI(decision.normalizedUrl).host.orEmpty() }.getOrDefault("")
        val adapter = adapters.firstOrNull { a -> a.allowedHosts.isEmpty() || UrlPolicy.isHostAllowed(host, a.allowedHosts) }
            ?: return SearchBatch(
                emptyList(),
                listOf(SourceSearchError("direct-url", "Прямая ссылка", "DIRECT_URL_SOURCE_NOT_FOUND", "Для этого домена нет включённого SNP.")),
            )
        val id = decision.normalizedUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
            .ifBlank { decision.normalizedUrl.hashCode().toUInt().toString(16) }
        return SearchBatch(
            listOf(
                VideoItem(
                    id = "${adapter.id}|$id",
                    remoteId = id,
                    watchUrl = decision.normalizedUrl,
                    title = id.replace('-', ' ').replace('_', ' '),
                    sourceName = adapter.name,
                    sourceBaseUrl = adapter.baseUrl,
                    sourceEngine = adapter.engine,
                    playerPageUrl = decision.normalizedUrl,
                    favoriteKey = "${adapter.id}|$id",
                ),
            ),
            emptyList(),
        )
    }

    /** Existing favorite/history items may still resolve after the source is disabled for search. */
    fun adapterFor(video: VideoItem): AdapterDefinition? {
        val adapters = allAdapters()
        // Two SNPs may share the same site. The card's ID identifies the actual provider.
        val sourceId = video.id.substringBefore('|').takeIf { '|' in video.id }.orEmpty()
        return adapters.firstOrNull { it.id.equals(sourceId, true) }
            ?: adapters.firstOrNull {
                it.baseUrl.equals(video.sourceBaseUrl, true) || it.name.equals(video.sourceName, true)
            }
    }

    private suspend fun searchOne(
        a: AdapterDefinition,
        q: String,
        page: Int,
        max: Int,
    ): Pair<List<VideoItem>, SourceSearchError?> {
        val engine = RuntimeEngineRegistry.find(a.engine)
            ?: return emptyList<VideoItem>() to SourceSearchError(a.id, a.name, "ENGINE_NOT_IMPLEMENTED", "Runtime engine ${a.engine} отсутствует.")
        val req = a.searchRequest(q, page, max)
        val candidates = searchCandidates(SearchUrls.candidates(engine, req), a.baseUrl, q, page, legacyFallbacks = a.engine != "auto_catalog")
        if (candidates.isEmpty()) return emptyList<VideoItem>() to SourceSearchError(a.id, a.name, "SEARCH_URL_EMPTY", "Engine не сформировал search URL.")
        var lastError: SourceSearchError? = null
        var hadSuccessfulResponse = false
        for (url in candidates) {
            val started = System.currentTimeMillis()
            val net = http.fetchText(url, NetworkPolicy(a.allowedHosts, allowCookies = a.allowCookies), a.id)
            val elapsed = System.currentTimeMillis() - started
            if (!net.success) {
                diagnostics.recordSearch(a.id, a.engine, net.finalUrl.ifBlank { url }, net.statusCode, elapsed, 0, net.errorCode)
                lastError = SourceSearchError(a.id, a.name, net.errorCode, net.errorMessage)
                continue
            }
            hadSuccessfulResponse = true
            val finalBase = originOf(net.finalUrl, a.baseUrl)
            val parseReq = req.copy(baseUrl = finalBase)
            val parsed = engine.parseSearch(net.text, parseReq)
            val items = parsed.mapNotNull { p ->
                val watch = normalizeAllowed(p.watchPath, a, baseUrl = finalBase) ?: return@mapNotNull null
                val thumb = normalizeAllowed(p.thumbnailUrl, a, allowBlank = true, baseUrl = finalBase).orEmpty()
                VideoItem(
                    id = "${a.id}|${p.remoteId.ifBlank { watch }}",
                    remoteId = p.remoteId,
                    watchUrl = watch,
                    title = p.title.ifBlank { p.remoteId.ifBlank { "Без названия" } },
                    thumbnailUrl = thumb,
                    duration = p.durationText,
                    views = p.viewsText,
                    sourceName = a.name,
                    sourceBaseUrl = a.baseUrl,
                    sourceEngine = a.engine,
                    playerPageUrl = watch,
                    favoriteKey = "${a.id}|${p.remoteId.ifBlank { watch }}",
                )
            }.distinctBy { it.watchUrl.lowercase() }.take(max.coerceAtLeast(1))
            diagnostics.recordSearch(a.id, a.engine, net.finalUrl.ifBlank { url }, net.statusCode, elapsed, items.size)
            if (items.isNotEmpty()) return items to null
        }
        if (hadSuccessfulResponse) return emptyList<VideoItem>() to SourceSearchError(a.id, a.name, "SEARCH_PARSED_ZERO", "Источник ответил, но текущий SNP не распознал ни одной видеокарточки.")
        return emptyList<VideoItem>() to (lastError ?: SourceSearchError(a.id, a.name, "SEARCH_FAILED", "Не удалось получить результаты поиска."))
    }

    /**
     * Adapter/engine template always wins. For legacy SNPs we additionally probe the common page
     * conventions used by KVS, WordPress, DLE and simple catalogs. This lets infinite scroll move
     * beyond page one even when the old SNP did not encode an explicit {page} placeholder.
     */
    private fun searchCandidates(primary: List<String>, baseUrl: String, query: String, page: Int, legacyFallbacks: Boolean): List<String> {
        val b = baseUrl.trimEnd('/')
        val q = pro.noodle.mobile.core.engine.runtime.EngineParsing.encodedQuery(query)
        val out = linkedSetOf<String>()
        primary.filter { it.isNotBlank() }.forEach { out += it }
        if (legacyFallbacks && b.isNotBlank() && q.isNotBlank()) {
            if (page < 0) {
                out += "$b/search/?q=$q"
                out += "$b/search/$q/"
                out += "$b/?s=$q"
            } else {
                val p = (page + 1).coerceAtLeast(2)
                out += "$b/search/$p/?q=$q"
                out += "$b/search/?q=$q&page=$p"
                out += "$b/search/?q=$q&p=$p"
                out += "$b/search/$q/page/$p/"
                out += "$b/search/$q/$p/"
                out += "$b/page/$p/?s=$q"
                out += "$b/?s=$q&paged=$p"
                out += "$b/?q=$q&page=$p"
            }
        }
        return out.take(MAX_SEARCH_CANDIDATES)
    }

    private fun originOf(url: String, fallback: String): String = runCatching {
        val u = URI(url)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) fallback
        else "${u.scheme}://${u.host}${if (u.port >= 0) ":${u.port}" else ""}"
    }.getOrDefault(fallback)

    private fun normalizeAllowed(raw: String, a: AdapterDefinition, allowBlank: Boolean = false, baseUrl: String = a.baseUrl): String? {
        if (raw.isBlank()) return if (allowBlank) "" else null
        val url = pro.noodle.mobile.core.engine.runtime.EngineParsing.absolute(raw, baseUrl)
        val d = UrlPolicy.evaluateNavigation(url, allowHttp = false, resolveDns = false)
        if (!d.allowed) return null
        val host = runCatching { URI(d.normalizedUrl).host.orEmpty() }.getOrDefault("")
        if (a.allowedHosts.isNotEmpty() && !UrlPolicy.isHostAllowed(host, a.allowedHosts)) return null
        return d.normalizedUrl
    }

    companion object {
        private const val SOURCE_SEARCH_TIMEOUT_MS = 10_000L
        private const val RELATED_SEARCH_TIMEOUT_MS = 6_000L
        private const val MAX_SEARCH_CANDIDATES = 10
    }
}
