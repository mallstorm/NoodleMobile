package pro.noodle.mobile.ui.search

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val resolving: Boolean = false,
    val items: List<VideoItem> = emptyList(),
    val errors: List<SourceSearchError> = emptyList(),
    val playback: PlaybackSession? = null,
    val message: String = "",
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    // A single client instance is deliberately shared so cookie-enabled SNP sessions remain isolated
    // by sourceKey but survive search -> resolve -> Media3 within this ViewModel lifetime.
    private val http = SecureHttpClient()
    private val searchRepo = BundledSearchRepository(app, http)
    private val playbackRepo = PlaybackRepository(app, http)
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var generation = 0L
    private var searchJob: Job? = null
    private var resolveJob: Job? = null
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
            policy = pro.noodle.mobile.core.network.NetworkPolicy(adapter.allowedHosts, allowCookies = adapter.allowCookies, maxResponseBytes = 3 * 1024 * 1024),
            sourceKey = adapter.id,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.5",
            referer = adapter.baseUrl,
        )
        if (!result.success || result.bytes.isEmpty()) { thumbnailMisses += url; return null }
        val bitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        if (bitmap == null) { thumbnailMisses += url; return null }
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
        resolveJob?.cancel()
        _state.update { it.copy(loading = true, resolving = false, message = "", playback = null) }
        searchJob = viewModelScope.launch {
            val batch = searchRepo.search(q)
            if (g != generation) return@launch
            _state.update {
                it.copy(
                    loading = false,
                    items = batch.items,
                    errors = batch.errors,
                    message = if (batch.items.isEmpty()) "Ничего не найдено" else "",
                )
            }
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
            val r = playbackRepo.resolve(video, adapter)
            _state.update {
                if (r.success && r.session != null) it.copy(resolving = false, playback = r.session)
                else it.copy(resolving = false, message = "${r.errorCode}: ${r.errorMessage}")
            }
        }
    }

    fun streamingClient(session: PlaybackSession): OkHttpClient =
        http.streamingClient(session.networkPolicy, session.sourceKey)

    fun closePlayer() {
        resolveJob?.cancel()
        _state.update { it.copy(playback = null, resolving = false) }
    }
}
