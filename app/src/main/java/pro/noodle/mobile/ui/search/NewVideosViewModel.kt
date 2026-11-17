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
)

class NewVideosViewModel(app: Application) : AndroidViewModel(app) {
    private val store = NewVideosStore(app)
    private val scanner = NewVideosScanner(app)
    private val settingsRepo = AppSettingsRepository(app)
    private val _state = MutableStateFlow(NewVideosState())
    val state: StateFlow<NewVideosState> = _state.asStateFlow()
    private var monitoring = true

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                monitoring = s.backgroundMonitoring
                _state.update { it.copy(windowHours = s.monitoringIntervalHours) }
                reload()
                // Автоскан при запуске, если давно не сканировали.
                if (monitoring && System.currentTimeMillis() - store.lastScanMs > 30 * 60_000L && !_state.value.scanning) scan()
            }
        }
    }

    private fun reload() {
        val cutoff = System.currentTimeMillis() - _state.value.windowHours * 3600_000L
        _state.update { st ->
            st.copy(items = store.entries().filter { it.seenAtMs >= cutoff }.sortedByDescending { it.seenAtMs }, lastScanMs = store.lastScanMs, sources = store.lastSources)
        }
    }

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, message = "") }
        viewModelScope.launch {
            val found = runCatching { scanner.scan() }.getOrDefault(-1)
            reload()
            _state.update { it.copy(scanning = false, message = when { found < 0 -> "Не удалось просканировать источники"; found == 0 -> "Новых роликов пока нет"; else -> "Найдено новых: $found" }) }
        }
    }
}
