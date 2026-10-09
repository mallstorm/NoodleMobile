package pro.noodle.mobile.ui.search

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.search.SourceSearchError
import pro.noodle.mobile.core.source.FileRefs
import pro.noodle.mobile.core.source.RuntimeAdapterRepository
import pro.noodle.mobile.core.source.SourceStatusStore
import pro.noodle.mobile.core.storage.UserMediaRepository

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val resolving: Boolean = false,
    val items: List<VideoItem> = emptyList(),
    val errors: List<SourceSearchError> = emptyList(),
    val playback: PlaybackSession? = null,
    val message: String = "",
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val http = SecureHttpClient()
    private val searchRepo = BundledSearchRepository(app, http)
    private val playbackRepo = PlaybackRepository(app, http)
    private val userMedia = UserMediaRepository(app)
    private val runtimeRepo = RuntimeAdapterRepository(app)
    private val semanticStore = SourceStatusStore(FileRefs.semantic(runtimeRepo.paths))
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var generation = 0L
    private var searchJob: Job? = null
    private var pagingJob: Job? = null
    private var resolveJob: Job? = null
    private var nextPage = 1
    private var lastPagingFinishedAtMs = 0L

    /**
     * Thumbnails used to be retained forever as full-size ARGB bitmaps. A single compressed
     * 2–3 MB JPEG can decode to 8–20 MB in memory, so a short result scroll could make MIUI's
     * low-memory killer terminate the process. Keep a hard byte-bounded cache instead.
     */
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val thumbnailMisses = ConcurrentHashMap.newKeySet<String>()

    suspend fun loadThumbnail(video: VideoItem): Bitmap? {
        val url = video.thumbnailUrl.trim()
        if (url.isBlank()) return null
        thumbnailCache.get(url)?.let { return it }
        if (url in thumbnailMisses) return null
        val adapter = searchRepo.adapterFor(video) ?: return null
        val result = http.fetchBytes(
            url = url,
            policy = pro.noodle.mobile.core.network.NetworkPolicy(
                adapter.allowedHosts,
                allowCookies = adapter.allowCookies,
                maxResponseBytes = THUMBNAIL_MAX_RESPONSE_BYTES,
            ),
            sourceKey = adapter.id,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.5",
            referer = adapter.baseUrl,
        )
        if (!result.success || result.bytes.isEmpty()) {
            thumbnailMisses += url
            return null
        }
        val bitmap = withContext(Dispatchers.Default) { decodeThumbnail(result.bytes) }
        if (bitmap == null) {
            thumbnailMisses += url
            return null
        }
        thumbnailCache.put(url, bitmap)
        return bitmap
    }

    /** Decode only a UI-sized preview instead of materialising the source image at full resolution. */
    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largestSide / sample > THUMBNAIL_TARGET_MAX_DIMENSION && sample < 32) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            thumbnailCache.evictAll()
            null
        }
    }

    fun setQuery(v: String) = _state.update { it.copy(query = v) }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isBlank()) return
        generation++
        val g = generation
        searchJob?.cancel()
        pagingJob?.cancel()
        resolveJob?.cancel()
        nextPage = 1
        lastPagingFinishedAtMs = 0L
        thumbnailCache.evictAll()
        thumbnailMisses.clear()
        _state.update {
            it.copy(
                loading = true,
                loadingMore = false,
                hasMore = true,
                resolving = false,
                items = emptyList(),
                errors = emptyList(),
                message = "",
                playback = null,
            )
        }
        searchJob = viewModelScope.launch {
            try {
                // Adapter verification, package hashing and HTML parsing are blocking/CPU-heavy.
                // Never execute them on the Compose/UI dispatcher.
                val batch = withContext(Dispatchers.IO) { searchRepo.search(q) }
                if (g != generation) return@launch
                val ranked = withContext(Dispatchers.IO) { rankItems(batch.items, q) }
                if (g != generation) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        items = ranked,
                        errors = batch.errors,
                        hasMore = batch.items.isNotEmpty(),
                        message = if (batch.items.isEmpty()) "Ничего не найдено" else "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (g != generation) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        hasMore = false,
                        message = "Ошибка поиска: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        val q = snapshot.query.trim()
        val now = SystemClock.elapsedRealtime()
        if (
            q.isBlank() || snapshot.loading || snapshot.loadingMore || !snapshot.hasMore || snapshot.items.isEmpty() ||
            now - lastPagingFinishedAtMs < PAGING_COOLDOWN_MS
        ) return

        val g = generation
        val startPage = nextPage
        val pages = (startPage until startPage + PAGE_BURST).toList()
        _state.update { it.copy(loadingMore = true, message = "") }
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            try {
                val batches = coroutineScope {
                    pages.map { page ->
                        async(Dispatchers.IO) { page to searchRepo.search(q, page = page, maxPerSource = PAGE_SIZE) }
                    }.awaitAll().sortedBy { it.first }
                }
                if (g != generation) return@launch
                val incoming = batches.flatMap { it.second.items }
                val old = _state.value.items
                val seen = old.mapTo(HashSet()) { stableKey(it) }
                val fresh = incoming.filter { seen.add(stableKey(it)) }
                nextPage = startPage + PAGE_BURST
                val pagingErrors = batches.flatMap { it.second.errors }
                    .distinctBy { "${it.sourceId}|${it.errorCode}|${it.message}" }
                val ranked = withContext(Dispatchers.IO) { rankItems(old + fresh, q) }
                if (g != generation) return@launch
                lastPagingFinishedAtMs = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(
                        loadingMore = false,
                        items = ranked,
                        errors = (it.errors + pagingErrors).distinctBy { e -> "${e.sourceId}|${e.errorCode}|${e.message}" },
                        hasMore = fresh.isNotEmpty(),
                        message = if (fresh.isEmpty()) "Больше результатов на следующих страницах не найдено" else "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (g != generation) return@launch
                lastPagingFinishedAtMs = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(
                        loadingMore = false,
                        message = "Ошибка догрузки: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * One deterministic product ranking used after every page merge. Exact query/title relevance
     * dominates, then source certification and metadata completeness. This keeps fast/healthy SNPs
     * above known-broken sources without hiding results from less tested adapters.
     */
    private fun rankItems(items: List<VideoItem>, query: String): List<VideoItem> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val adapterScores = HashMap<String, Int>()
        fun sourceScore(video: VideoItem): Int {
            val adapter = searchRepo.adapterFor(video) ?: return 0
            return adapterScores.getOrPut(adapter.id.lowercase()) {
                val status = semanticStore.get(adapter.id, "status")
                val ready = semanticStore.getInt(adapter.id, "playback_count")
                val search = semanticStore.getInt(adapter.id, "search_count")
                when (status) {
                    "healthy" -> 35 + (ready.coerceAtMost(5) * 3) + search.coerceAtMost(10)
                    "no_results" -> 5
                    "playback_failed", "search_failed", "invalid_adapter", "error" -> -35
                    else -> 0
                }
            }
        }
        fun score(video: VideoItem): Int {
            val title = video.title.lowercase()
            var score = sourceScore(video)
            if (terms.isNotEmpty()) {
                val matched = terms.count { it in title }
                score += matched * 25
                if (matched == terms.size) score += 35
                if (title.startsWith(query.lowercase())) score += 10
            }
            if (video.thumbnailUrl.isNotBlank()) score += 5
            if (video.duration.isNotBlank()) score += 4
            if (video.views.isNotBlank()) score += 3
            if (video.remoteId.isNotBlank()) score += 2
            return score
        }
        return items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<VideoItem>> { score(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    fun addFavorite(video: VideoItem) {
        viewModelScope.launch {
            userMedia.addFavorite(video)
            _state.update { it.copy(message = "Добавлено в избранное") }
        }
    }

    fun open(video: VideoItem) {
        val adapter = searchRepo.adapterFor(video) ?: run {
            _state.update { it.copy(message = "SNP источника не найден.") }
            return
        }
        resolveJob?.cancel()
        _state.update { it.copy(resolving = true, message = "") }
        resolveJob = viewModelScope.launch {
            userMedia.saveHistory(video, 0L, 0L)
            val r = playbackRepo.resolve(video, adapter)
            _state.update {
                if (r.success && r.session != null) {
                    it.copy(resolving = false, playback = r.session)
                } else {
                    it.copy(resolving = false, message = "${r.errorCode}: ${r.errorMessage}")
                }
            }
        }
    }

    fun streamingClient(session: PlaybackSession): OkHttpClient =
        http.streamingClient(session.networkPolicy, session.sourceKey)

    fun closePlayer() {
        resolveJob?.cancel()
        _state.update { it.copy(playback = null, resolving = false) }
    }

    override fun onCleared() {
        thumbnailCache.evictAll()
        thumbnailMisses.clear()
        super.onCleared()
    }

    private fun stableKey(v: VideoItem): String =
        "${v.sourceBaseUrl}|${v.remoteId}|${v.watchUrl}".lowercase()

    companion object {
        private const val PAGE_BURST = 1
        private const val PAGE_SIZE = 18
        private const val PAGING_COOLDOWN_MS = 1_500L
        private const val THUMBNAIL_CACHE_KB = 16 * 1024
        private const val THUMBNAIL_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val THUMBNAIL_TARGET_MAX_DIMENSION = 512
    }
}
