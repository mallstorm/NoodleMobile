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
import kotlinx.coroutines.delay
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

    init {
        // Историю запросов приложение больше не хранит: чистим то, что осталось от прошлых версий.
        app.getSharedPreferences("noodle_search_history", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    private var generation = 0L
    /** Все найденные ролики без склейки: ранжирование и склейка дублей пересчитываются из них при каждой догрузке. */
    private var rawItems: List<VideoItem> = emptyList()
    private var alternates: Map<String, List<VideoItem>> = emptyMap()
    private var searchJob: Job? = null
    private var pagingJob: Job? = null
    private var resolveJob: Job? = null
    private var nextPage = 1
    private var lastPagingFinishedAtMs = 0L
    private var emptyStreak = 0
    private var pagingFailures = 0

    private fun prefetchLoop(g: Long) {
        viewModelScope.launch {
            var rounds = 0
            while (g == generation && rounds < PREFETCH_ROUNDS) {
                val st = _state.value
                if (!st.hasMore || st.items.size >= PREFETCH_TARGET) break
                if (st.loadingMore) { delay(300); rounds++; continue }
                lastPagingFinishedAtMs = 0L
                loadMore()
                delay(250)
                while (g == generation && _state.value.loadingMore) delay(200)
                rounds++
            }
        }
    }

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
        val adapter = searchRepo.adapterFor(video)
        val result = pro.noodle.mobile.core.media.ThumbnailFetcher.fetch(
            http, video, adapter?.baseUrl.orEmpty(), adapter?.id ?: video.sourceName.ifBlank { "thumbnails" },
            adapter?.allowCookies ?: false, THUMBNAIL_MAX_RESPONSE_BYTES,
        )
        val bytes = result.bytes
        if (bytes == null || bytes.isEmpty()) {
            if (result.status != 0) thumbnailMisses += url
            return null
        }
        val bitmap = withContext(Dispatchers.Default) { decodeThumbnail(bytes) }
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
        emptyStreak = 0
        pagingFailures = 0
        lastPagingFinishedAtMs = 0L
        thumbnailCache.evictAll()
        thumbnailMisses.clear()
        rawItems = emptyList()
        alternates = emptyMap()
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
                val batch = withContext(Dispatchers.IO) { searchRepo.search(q, maxPerSource = INITIAL_PER_SOURCE) }
                if (g != generation) return@launch
                rawItems = batch.items
                val ranked = withContext(Dispatchers.IO) { mergeDuplicates(rankItems(batch.items, q)) }
                if (g != generation) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        items = ranked,
                        errors = batch.errors,
                        hasMore = true,
                        message = if (batch.items.isEmpty()) "Ничего не найдено" else "",
                    )
                }
                // Лента должна быть «предзагружена»: сразу дотягиваем следующие страницы,
                // пока не наберётся комфортный запас карточек.
                if (batch.items.isNotEmpty()) prefetchLoop(g)
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
                val old = rawItems
                val seen = old.mapTo(HashSet()) { stableKey(it) }
                val fresh = incoming.filter { seen.add(stableKey(it)) }
                nextPage = startPage + PAGE_BURST
                val cleanEmpty = fresh.isEmpty() && batches.all { it.second.errors.isEmpty() }
                if (fresh.isNotEmpty()) emptyStreak = 0 else if (cleanEmpty) emptyStreak++ else emptyStreak = emptyStreak.coerceAtLeast(0)
                if (fresh.isEmpty() && !cleanEmpty) pagingFailures++ else pagingFailures = 0
                rawItems = old + fresh
                val ranked = withContext(Dispatchers.IO) { mergeDuplicates(rankItems(rawItems, q)) }
                if (g != generation) return@launch
                lastPagingFinishedAtMs = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(
                        loadingMore = false,
                        items = ranked,
                        // Дозагрузка ленты молча: конец выдачи или сбой страницы — это не ошибка источника.
                        errors = it.errors,
                        hasMore = emptyStreak < MAX_EMPTY_STREAK && (fresh.isNotEmpty() || pagingFailures < MAX_FAILED_STREAK),
                        message = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (g != generation) return@launch
                lastPagingFinishedAtMs = SystemClock.elapsedRealtime()
                pagingFailures++
                _state.update {
                    it.copy(
                        loadingMore = false,
                        hasMore = pagingFailures < MAX_FAILED_STREAK,
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
        val latinTerms = (pro.noodle.mobile.core.source.SourceNames.latinQuery(query) ?: "").lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
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
                val matched = terms.indices.count { i -> terms[i] in title || latinTerms.getOrNull(i)?.let { it in title } == true }
                score += matched * 25
                if (matched == terms.size) score += 35
                if (title.startsWith(query.lowercase()) || latinTerms.isNotEmpty() && title.startsWith(latinTerms.joinToString(" "))) score += 10
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

    private fun normTitle(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun durationSec(d: String): Int {
        val p = d.trim().split(':').map { it.toIntOrNull() ?: return 0 }
        return when (p.size) { 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> 0 }
    }

    /**
     * Склейка: один и тот же ролик с разных сайтов показывается одной карточкой (лучшая по рангу),
     * остальные копии запоминаются как запасные источники — если первая не откроется, пробуем их.
     * Склеиваем только при совпадении нормализованного названия (≥12 символов) и длительности ±5 с
     * (если длительность указана у обоих) и только между разными сайтами.
     */
    private fun mergeDuplicates(ranked: List<VideoItem>): List<VideoItem> {
        val primaries = LinkedHashMap<String, VideoItem>()
        val alts = HashMap<String, MutableList<VideoItem>>()
        val byTitle = HashMap<String, MutableList<String>>()
        for (v in ranked) {
            val key = normTitle(v.title)
            val id = v.id.ifBlank { stableKey(v) }
            var target: String? = null
            if (key.length >= 12) {
                val d = durationSec(v.duration)
                target = byTitle[key].orEmpty().firstOrNull { pid ->
                    val p = primaries[pid]!!
                    val pd = durationSec(p.duration)
                    p.sourceBaseUrl != v.sourceBaseUrl && (d == 0 || pd == 0 || kotlin.math.abs(d - pd) <= 5)
                }
            }
            if (target != null) alts.getOrPut(target) { mutableListOf() }.add(v)
            else { primaries[id] = v; if (key.length >= 12) byTitle.getOrPut(key) { mutableListOf() }.add(id) }
        }
        alternates = alts
        return primaries.values.toList()
    }

    fun altCount(video: VideoItem): Int = alternates[video.id.ifBlank { stableKey(video) }]?.size ?: 0

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
            var r = playbackRepo.resolve(video, adapter)
            if (!(r.success && r.session != null)) {
                for (alt in alternates[video.id.ifBlank { stableKey(video) }].orEmpty()) {
                    val a = searchRepo.adapterFor(alt) ?: continue
                    val rr = playbackRepo.resolve(alt, a)
                    if (rr.success && rr.session != null) { r = rr; break }
                }
            }
            _state.update {
                if (r.success && r.session != null) {
                    it.copy(resolving = false, playback = r.session)
                } else if (pro.noodle.mobile.core.search.ServiceCardFilter.isNoMediaCode(r.errorCode)) {
                    // Не ролик (служебная/пустая страница): карточку убираем из ленты, без красной плашки.
                    rawItems = rawItems.filterNot { x -> x.watchUrl.equals(video.watchUrl, true) }
                    it.copy(resolving = false, message = "", items = it.items.filterNot { x -> x.watchUrl.equals(video.watchUrl, true) })
                } else {
                    it.copy(resolving = false, message = "${r.errorCode}: ${r.errorMessage}")
                }
            }
        }
    }

    fun streamingClient(session: PlaybackSession): OkHttpClient =
        http.streamingClient(session.networkPolicy, session.sourceKey)

    fun clearMessage() = _state.update { it.copy(message = "") }

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
        private const val MAX_RECENT = 12
        private const val INITIAL_PER_SOURCE = 40
        private const val PREFETCH_TARGET = 60
        private const val PREFETCH_ROUNDS = 4
        private const val MAX_EMPTY_STREAK = 2
        private const val MAX_FAILED_STREAK = 4
        private const val PAGE_BURST = 2
        private const val PAGE_SIZE = 24
        private const val PAGING_COOLDOWN_MS = 400L
        private const val THUMBNAIL_CACHE_KB = 16 * 1024
        private const val THUMBNAIL_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val THUMBNAIL_TARGET_MAX_DIMENSION = 512
    }
}
