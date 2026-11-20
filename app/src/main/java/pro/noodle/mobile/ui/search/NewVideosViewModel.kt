package pro.noodle.mobile.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.newvideos.NewEntry
import pro.noodle.mobile.core.newvideos.NewVideosScanner
import pro.noodle.mobile.core.newvideos.NewVideosStore
import pro.noodle.mobile.core.settings.AppSettingsRepository

data class NewVideosState(
    val items: List<NewEntry> = emptyList(),
    val windowHours: Int = 24,
    val scanning: Boolean = false,
    val lastScanMs: Long = 0L,
    val sources: Int = 0,
    val message: String = "",
    val viewed: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),
    val grouped: Boolean = false,
    val onlyUnviewed: Boolean = false,
)

class NewVideosViewModel(app: Application) : AndroidViewModel(app) {
    private val store = NewVideosStore(app)
    private val scanner = NewVideosScanner(app)
    private val settingsRepo = AppSettingsRepository(app)
    private val _state = MutableStateFlow(NewVideosState())
    val state: StateFlow<NewVideosState> = _state.asStateFlow()
    private var monitoring = true
    private var autoDone = false

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                monitoring = s.backgroundMonitoring
                _state.update { it.copy(windowHours = s.monitoringIntervalHours) }
                reload()
                // Автоскан при запуске, если давно не сканировали.
                if (monitoring && !autoDone && System.currentTimeMillis() - store.lastScanMs > 30 * 60_000L && !_state.value.scanning) {
                    autoDone = true
                    kotlinx.coroutines.delay(25_000) // не мешаем запуску и первым открытиям видео
                    if (!_state.value.scanning) scan()
                }
            }
        }
    }

    private fun reload() {
        val cutoff = System.currentTimeMillis() - _state.value.windowHours * 3600_000L
        _state.update { st ->
            st.copy(items = store.entries().filter { it.seenAtMs >= cutoff }.sortedByDescending { it.seenAtMs }, lastScanMs = store.lastScanMs, sources = store.lastSources, viewed = store.viewed(), muted = store.muted())
        }
    }

    fun toggleGrouped() = _state.update { it.copy(grouped = !it.grouped) }
    fun toggleOnlyUnviewed() = _state.update { it.copy(onlyUnviewed = !it.onlyUnviewed) }
    fun markViewed(video: pro.noodle.mobile.core.model.VideoItem) { store.markViewed(listOf(video.id)); reload() }
    fun markAllViewed() { store.markViewed(_state.value.items.map { it.video.id }); reload() }
    fun toggleMute(source: String) { store.setMuted(source, source !in store.muted()); reload() }

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, message = "") }
        viewModelScope.launch {
            val found = runCatching { scanner.scan() }.getOrDefault(-1)
            reload()
            _state.update { it.copy(scanning = false, message = when { found < 0 -> "Не удалось просканировать источники"; found == 0 -> "Новых роликов пока нет. ${store.lastReport}"; else -> "Найдено новых: $found" }) }
        }
    }
}
