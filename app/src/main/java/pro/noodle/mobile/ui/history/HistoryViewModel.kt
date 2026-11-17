package pro.noodle.mobile.ui.history

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.NetworkPolicy
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.storage.HistoryItem
import pro.noodle.mobile.core.storage.UserMediaRepository
import java.util.concurrent.ConcurrentHashMap

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val resolving: Boolean = false,
    val playback: PlaybackSession? = null,
    val message: String = "",
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val user = UserMediaRepository(app)
    private val http = SecureHttpClient()
    private val sources = BundledSearchRepository(app, http)
    private val playbackRepo = PlaybackRepository(app, http)
    private val mutable = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = combine(user.history(), mutable) { items, state -> state.copy(items = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val thumbnailMisses = ConcurrentHashMap.newKeySet<String>()

    suspend fun loadThumbnail(video: VideoItem): Bitmap? {
        val url = video.thumbnailUrl.trim()
        if (url.isBlank()) return null
        thumbnailCache[url]?.let { return it }
        if (url in thumbnailMisses) return null
        val adapter = sources.adapterFor(video)
        val result = pro.noodle.mobile.core.media.ThumbnailFetcher.fetch(
            http, video, adapter?.baseUrl.orEmpty(), adapter?.id ?: video.sourceName.ifBlank { "thumbnails" }, adapter?.allowCookies ?: false,
        )
        val bytes = result.bytes
        if (bytes == null || bytes.isEmpty()) { if (result.status != 0) thumbnailMisses += url; return null }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) { thumbnailMisses += url; return null }
        thumbnailCache[url] = bitmap
        return bitmap
    }

    fun open(item: HistoryItem) {
        val video = item.video
        val adapter = sources.adapterFor(video) ?: run {
            mutable.update { it.copy(message = "SNP источника не найден") }
            return
        }
        mutable.update { it.copy(resolving = true, message = "") }
        viewModelScope.launch {
            val r = playbackRepo.resolve(video, adapter)
            mutable.update {
                if (r.success && r.session != null) it.copy(resolving = false, playback = r.session)
                else it.copy(resolving = false, message = "${r.errorCode}: ${r.errorMessage}")
            }
        }
    }

    fun remove(item: HistoryItem) = viewModelScope.launch { user.removeHistory(item.video) }
    fun clear() = viewModelScope.launch { user.clearHistory() }
    fun closePlayer() = mutable.update { it.copy(playback = null, resolving = false) }
    fun streamingClient(session: PlaybackSession): OkHttpClient = http.streamingClient(session.networkPolicy, session.sourceKey)
}
