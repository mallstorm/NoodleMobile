package pro.noodle.mobile.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.download.DownloadRepository
import pro.noodle.mobile.core.download.OfflineMediaRepository
import pro.noodle.mobile.core.download.OfflinePlaybackSpec
import pro.noodle.mobile.core.storage.DownloadEntity

@OptIn(UnstableApi::class)
class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DownloadRepository(app)
    private val offlineRepo = OfflineMediaRepository(app)

    val items: StateFlow<List<DownloadEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val completed: StateFlow<List<DownloadEntity>> = repo.observeCompleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _offlinePlayback = MutableStateFlow<OfflinePlaybackSpec?>(null)
    val offlinePlayback: StateFlow<OfflinePlaybackSpec?> = _offlinePlayback.asStateFlow()
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    fun pause(id: String) = viewModelScope.launch { repo.pause(id) }
    fun resume(id: String) = viewModelScope.launch { repo.resume(id) }
    fun cancel(id: String) = viewModelScope.launch { repo.cancel(id) }
    fun remove(id: String) = viewModelScope.launch {
        if (_offlinePlayback.value?.id == id) _offlinePlayback.value = null
        repo.remove(id)
    }

    fun openOffline(id: String) = viewModelScope.launch {
        offlineRepo.open(id).onSuccess {
            _message.value = ""
            _offlinePlayback.value = it
        }.onFailure {
            _message.value = it.message ?: "Не удалось открыть офлайн-медиа"
        }
    }

    fun closeOffline() { _offlinePlayback.value = null }
}
