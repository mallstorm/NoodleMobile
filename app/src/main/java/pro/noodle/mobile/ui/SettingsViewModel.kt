package pro.noodle.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.diagnostics.DiagnosticsService
import pro.noodle.mobile.core.download.MediaDownloadRuntime
import pro.noodle.mobile.core.maintenance.SourceMaintenanceScheduler
import pro.noodle.mobile.core.security.AppLockCrypto
import pro.noodle.mobile.core.settings.AppSettings
import pro.noodle.mobile.core.settings.AppSettingsRepository

@OptIn(UnstableApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val diagnostics = DiagnosticsService(app)
    private val _diagnosticsMessage = MutableStateFlow("")
    val diagnosticsMessage: StateFlow<String> = _diagnosticsMessage.asStateFlow()
    private val repo = AppSettingsRepository(app)
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    init {
        viewModelScope.launch {
            repo.settings.collect {
                SourceMaintenanceScheduler.sync(app, it.backgroundMonitoring, it.monitoringIntervalHours)
            }
        }
    }

    fun theme(v: String) = viewModelScope.launch { repo.setTheme(v) }
    fun uiScale(v: Int) = viewModelScope.launch { repo.setUiScalePercent(v) }
    fun parallel(v: Int) = viewModelScope.launch {
        val n = v.coerceIn(1, 3)
        repo.setParallelDownloads(n)
        MediaDownloadRuntime.manager(getApplication()).maxParallelDownloads = n
    }
    fun background(v: Boolean) = viewModelScope.launch { repo.setBackgroundMonitoring(v) }
    fun monitoringHours(v: Int) = viewModelScope.launch { repo.setMonitoringIntervalHours(v) }
    fun rememberPosition(v: Boolean) = viewModelScope.launch { repo.setRememberPlaybackPosition(v) }

    fun setLock(mode: String, secret: String = "") = viewModelScope.launch {
        when (mode) {
            "pin", "pattern" -> {
                val salt = AppLockCrypto.newSalt()
                repo.setAppLock(mode, salt, AppLockCrypto.hash(secret, salt))
            }
            "biometric" -> repo.setAppLock("biometric")
            else -> repo.setAppLock("none")
        }
    }

    fun exportDiagnostics(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        _diagnosticsMessage.value = "Сбор диагностики…"
        runCatching {
            val zip = diagnostics.createZip()
            app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: error("Не удалось открыть файл назначения")
            zip.delete()
        }.onSuccess {
            _diagnosticsMessage.value = "Диагностический ZIP сохранён."
        }.onFailure {
            _diagnosticsMessage.value = "DIAGNOSTICS_EXPORT_FAILED: ${it.message}"
        }
    }
}
