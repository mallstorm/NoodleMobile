package pro.noodle.mobile.ui.search

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.search.SourceSearchError
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
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var generation = 0L
    private var searchJob: Job? = null
    private var pagingJob: Job? = null
    private var resolveJob: Job? = null
    private var nextPage = 1
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val thumbnailMisses = ConcurrentHashMap.newKeySet<String>()

    suspend fun loadThumbnail(video: VideoItem): Bitmap? {
        val url = video.thumbnailUrl.trim()
        if (url.isBlank()) return null
        thumbnailCache[url]?.let { return it }
        if (url in thumbnailMisses) return null
        val adapter = searchRepo.adapterFor(video) ?: return null
        val result = http.fetchBytes(
            url = url,
            policy = pro.noodle.mobile.core.network.NetworkPolicy(
                adapter.allowedHosts,
                allowCookies = adapter.allowCookies,
                maxResponseBytes = 3 * 1024 * 1024,
            ),
            sourceKey = adapter.id,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.5",
            referer = adapter.baseUrl,
        )
        if (!result.success || result.bytes.isEmpty()) {
            thumbnailMisses += url
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        if (bitmap == null) {
            thumbnailMisses += url
            return null
        }
        thumbnailCache[url] = bitmap
        return bitmap
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
            val batch = searchRepo.search(q)
            if (g != generation) return@launch
            _state.update {
                it.copy(
                    loading = false,
                    items = batch.items,
                    errors = batch.errors,
                    hasMore = batch.items.isNotEmpty(),
                    message = if (batch.items.isEmpty()) "Ничего не найдено" else "",
                )
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        val q = snapshot.query.trim()
        if (q.isBlank() || snapshot.loading || snapshot.loadingMore || !snapshot.hasMore || snapshot.items.isEmpty()) return
        val g = generation
        val startPage = nextPage
        val pages = (startPage until startPage + PAGE_BURST).toList()
        _state.update { it.copy(loadingMore = true, message = "") }
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            val batches = coroutineScope {
                pages.map { page ->
                    async { page to searchRepo.search(q, page = page, maxPerSource = PAGE_SIZE) }
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
            _state.update {
                it.copy(
                    loadingMore = false,
                    items = old + fresh,
                    errors = (it.errors + pagingErrors).distinctBy { e -> "${e.sourceId}|${e.errorCode}|${e.message}" },
                    hasMore = fresh.isNotEmpty(),
                    message = if (fresh.isEmpty()) "Больше результатов на следующих страницах не найдено" else "",
                )
            }
        }
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

    private fun stableKey(v: VideoItem): String =
        "${v.sourceBaseUrl}|${v.remoteId}|${v.watchUrl}".lowercase()

    companion object {
        private const val PAGE_BURST = 4
        private const val PAGE_SIZE = 30
    }
}
