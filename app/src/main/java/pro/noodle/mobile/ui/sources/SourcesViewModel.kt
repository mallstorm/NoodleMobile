package pro.noodle.mobile.ui.sources

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.source.AdapterSnapshotInfo
import pro.noodle.mobile.core.source.SourceActionResult
import pro.noodle.mobile.core.source.SourceCenterItem
import pro.noodle.mobile.core.source.SourceCenterService
import pro.noodle.mobile.core.source.SourceDetails
import java.io.File

data class SourcesUiState(
    val loading: Boolean = true,
    val adapters: List<SourceCenterItem> = emptyList(),
    val busyId: String = "",
    val message: String = "",
    val error: String = "",
    val historyFor: String = "",
    val history: List<AdapterSnapshotInfo> = emptyList(),
    val details: SourceDetails? = null,
)

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val service = SourceCenterService(app)
    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            val changed = runCatching { service.autoHealth() }.getOrDefault(false)
            if (changed) runCatching { service.enumerate() }.onSuccess { items -> _state.update { it.copy(adapters = items) } }
        }
    }

    fun reload() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = "") }
        runCatching { service.enumerate() }
            .onSuccess { items -> _state.update { it.copy(loading = false, adapters = items) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) } }
    }

    fun setEnabled(id: String, enabled: Boolean) = action(id) { service.setEnabled(id, enabled) }
    fun health(id: String) = action(id) { service.health(id) }
    fun semantic(id: String) = action(id) { service.semantic(id) }

    /** Live check of every enabled source: search + real playback, one after another. */
    fun checkAll() = viewModelScope.launch {
        val targets = _state.value.adapters.filter { it.enabled }
        if (targets.isEmpty()) { _state.update { it.copy(message = "Нет включённых источников.", error = "") }; return@launch }
        var ok = 0
        val failed = mutableListOf<String>()
        for (t in targets) {
            _state.update { it.copy(busyId = t.adapterId, message = "Проверяю ${t.name}…", error = "") }
            val r = runCatching { service.semantic(t.adapterId) }.getOrElse { SourceActionResult(false, errorCode = "SOURCE_CENTER_EXCEPTION", message = it.message.orEmpty()) }
            if (r.success) ok++ else failed += t.name
            val items = runCatching { service.enumerate() }.getOrDefault(_state.value.adapters)
            _state.update { it.copy(adapters = items) }
        }
        _state.update {
            it.copy(
                busyId = "",
                message = "Проверено ${targets.size}: работает $ok" + if (failed.isEmpty()) "." else ", с ошибками: ${failed.joinToString()}.",
            )
        }
    }
    fun fingerprint(id: String) = action(id) { service.fingerprint(id) }
    fun breakage(id: String) = action(id) { service.breakage(id) }
    fun repair(id: String) = action(id) { service.repair(id) }
    fun migrate(id: String) = action(id) { service.migrate(id) }
    fun checkUpdate(id: String) = action(id) { service.checkUpdate(id) }
    fun applyUpdate(id: String) = action(id) { service.applyUpdate(id) }
    fun rollback(id: String) = action(id) { service.rollback(id) }
    fun removeCustom(id: String) = action(id) { service.removeCustom(id) }

    fun showDetails(id:String) = _state.update { it.copy(details=service.details(id), message="") }
    fun closeDetails() = _state.update { it.copy(details=null) }

    fun showHistory(id: String) {
        _state.update { it.copy(historyFor = id, history = service.history(id), message = "") }
    }

    fun closeHistory() = _state.update { it.copy(historyFor = "", history = emptyList()) }
    fun restore(id: String, snapshotId: String) = action(id) { service.restore(id, snapshotId) }.also { closeHistory() }

    fun importZip(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busyId = "@import", message = "", error = "") }
        val temp = File(getApplication<Application>().cacheDir, "snp-import-${System.currentTimeMillis()}.zip")
        val result = try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: return@launch _state.update { it.copy(busyId = "", error = "Не удалось открыть выбранный ZIP.") }
            service.importZip(temp)
        } catch (e: Exception) {
            SourceActionResult(false, errorCode = "SNP_IMPORT_IO_ERROR", message = "${e.javaClass.simpleName}: ${e.message}")
        } finally { temp.delete() }
        finish(result)
    }

    fun exportToUri(id: String, uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busyId = id, message = "", error = "") }
        val temp = File(getApplication<Application>().cacheDir, "${id.lowercase()}-${System.currentTimeMillis()}.snp.zip")
        val result = try {
            val built = service.exportToFile(id, temp)
            if (!built.success) built else {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ?: return@launch _state.update { it.copy(busyId = "", error = "Не удалось открыть файл назначения.") }
                SourceActionResult(true, "exported", "SNP экспортирован.")
            }
        } catch (e: Exception) {
            SourceActionResult(false, errorCode = "SNP_EXPORT_IO_ERROR", message = "${e.javaClass.simpleName}: ${e.message}")
        } finally { temp.delete() }
        finish(result)
    }

    private fun action(id: String, block: suspend () -> SourceActionResult) = viewModelScope.launch {
        _state.update { it.copy(busyId = id, message = "", error = "") }
        val r = runCatching { block() }.getOrElse { SourceActionResult(false, errorCode = "SOURCE_CENTER_EXCEPTION", message = "${it.javaClass.simpleName}: ${it.message}") }
        finish(r)
    }

    private suspend fun finish(result: SourceActionResult) {
        val items = runCatching { service.enumerate() }.getOrDefault(_state.value.adapters)
        _state.update { it.copy(busyId = "", adapters = items, message = if (result.success) result.message else "", error = if (result.success) "" else "${result.errorCode}: ${result.message}") }
    }
}
