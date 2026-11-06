package pro.noodle.mobile.ui.sources

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.noodle.mobile.core.catalog.CatalogEntry
import pro.noodle.mobile.core.catalog.CatalogSettingsRepository
import pro.noodle.mobile.core.catalog.MarketplaceRepository
import pro.noodle.mobile.core.catalog.TrustedCatalogKey
import pro.noodle.mobile.core.catalog.VerifiedCatalog
import pro.noodle.mobile.core.studio.AdapterStudioService
import pro.noodle.mobile.core.studio.StudioAiCandidateRepairService
import pro.noodle.mobile.core.studio.StudioAiModel
import pro.noodle.mobile.core.studio.StudioAiProvider
import pro.noodle.mobile.core.studio.StudioAiRouter
import pro.noodle.mobile.core.studio.StudioBackupService
import pro.noodle.mobile.core.studio.StudioBrowserRecoveryService


data class SourceToolsUiState(
    val catalogUrl: String = "",
    val catalog: VerifiedCatalog? = null,
    val marketplaceBusy: Boolean = false,
    val trustedKeys: List<TrustedCatalogKey> = emptyList(),
    val message: String = "",
    val error: String = "",
    val studioUrl: String = "",
    val studioInstruction: String = "",
    val studioJson: String = "",
    val studioBusy: Boolean = false,
    val studioMessage: String = "",
    val studioProvider: StudioAiProvider = StudioAiProvider.LOCAL,
    val studioModel: String = "",
    val studioModels: List<String> = emptyList(),
    val studioModelCatalog: List<StudioAiModel> = emptyList(),
    val studioConnected: Boolean = true,
    val studioAccount: String = "Локальный анализ",
    val studioAuthBusy: Boolean = false,
    val studioLoginUrl: String = "",
    val studioLoginCode: String = "",
    val studioTestStatus: String = "",
    val studioTestDetails: String = "",
    val studioAttempts: Int = 0,
    // Simple «site + query» Studio
    val studioQuery: String = "",
    val autoBusy: Boolean = false,
    val autoSteps: List<String> = emptyList(),
    val autoDone: Boolean = false,
    val autoHealthy: Boolean = false,
    val autoSiteName: String = "",
    val autoAdapterId: String = "",
    val autoSamples: List<StudioSample> = emptyList(),
    val autoSearchCount: Int = 0,
    val autoPlaybackReady: Int = 0,
    val autoInstalled: Boolean = false,
    val autoError: String = "",
)

data class StudioSample(val title: String, val thumbnailUrl: String, val duration: String)

class SourceToolsViewModel(app: Application) : AndroidViewModel(app) {
    private val market = MarketplaceRepository(app)
    private val catalogSettings = CatalogSettingsRepository(app)
    private val studio = AdapterStudioService(app)
    private val studioBrowser = StudioBrowserRecoveryService(app)
    private val studioAiRepair = StudioAiCandidateRepairService(app)
    private val studioBackup = StudioBackupService(app)
    private val studioAi = StudioAiRouter(app)
    private val initialProvider = studioAi.selectedProvider()
    private val initialAccount = studioAi.status(initialProvider)

    private val _state = MutableStateFlow(
        SourceToolsUiState(
            trustedKeys = market.trustedKeys().list(),
            studioProvider = initialProvider,
            studioModel = studioAi.selectedModel(initialProvider),
            studioConnected = initialAccount.connected,
            studioAccount = initialAccount.accountLabel,
        ),
    )
    val state: StateFlow<SourceToolsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            catalogSettings.settings.collect { settings ->
                _state.update { it.copy(catalogUrl = settings.indexUrl) }
            }
        }
        if (initialProvider != StudioAiProvider.LOCAL && initialAccount.connected) refreshStudioModels()
    }

    fun setCatalogUrl(value: String) = _state.update { it.copy(catalogUrl = value) }

    fun saveCatalogUrl() = viewModelScope.launch {
        market.saveIndexUrl(_state.value.catalogUrl)
        _state.update { it.copy(message = "URL каталога сохранён.", error = "") }
    }

    fun refreshCatalog() = viewModelScope.launch {
        _state.update { it.copy(marketplaceBusy = true, message = "", error = "") }
        val result = market.refresh(_state.value.catalogUrl)
        _state.update {
            it.copy(
                marketplaceBusy = false,
                catalog = if (result.success) result.catalog else it.catalog,
                message = if (result.success) result.message else "",
                error = if (result.success) "" else "${result.errorCode}: ${result.message}",
                trustedKeys = market.trustedKeys().list(),
            )
        }
    }

    fun install(entry: CatalogEntry, onDone: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(marketplaceBusy = true, message = "", error = "") }
        val result = market.install(entry)
        _state.update {
            it.copy(
                marketplaceBusy = false,
                message = if (result.success) result.message else "",
                error = if (result.success) "" else "${result.errorCode}: ${result.message}",
            )
        }
        onDone()
    }

    fun importKeys(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val text = runCatching {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrElse { error ->
            _state.update { it.copy(error = "KEY_IMPORT_IO: ${error.message}") }
            return@launch
        }
        val result = market.trustedKeys().importRegistry(text)
        _state.update {
            it.copy(
                trustedKeys = market.trustedKeys().list(),
                message = if (result.valid) result.message else "",
                error = if (result.valid) "" else "${result.errorCode}: ${result.message}",
            )
        }
    }

    fun exportKeys(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(market.trustedKeys().exportUserRegistry())
            } ?: error("Не удалось открыть файл назначения")
        }.onSuccess {
            _state.update { it.copy(message = "Пользовательские trusted keys экспортированы.", error = "") }
        }.onFailure { error ->
            _state.update { it.copy(error = "KEY_EXPORT_IO: ${error.message}") }
        }
    }

    fun removeKey(id: String) {
        val result = market.trustedKeys().remove(id)
        _state.update {
            it.copy(
                trustedKeys = market.trustedKeys().list(),
                message = if (result.valid) result.message else "",
                error = if (result.valid) "" else "${result.errorCode}: ${result.message}",
            )
        }
    }

    fun setStudioUrl(value: String) = _state.update { it.copy(studioUrl = value) }
    fun setStudioQuery(value: String) = _state.update { it.copy(studioQuery = value) }

    fun resetAuto() = _state.update {
        it.copy(autoBusy = false, autoSteps = emptyList(), autoDone = false, autoHealthy = false, autoSiteName = "", autoAdapterId = "", autoSamples = emptyList(), autoSearchCount = 0, autoPlaybackReady = 0, autoInstalled = false, autoError = "")
    }

    /** One-button flow: discover → learn → live test → (optionally) install and enable. */
    fun autoCreate() = viewModelScope.launch {
        val st = _state.value
        if (st.autoBusy || st.studioUrl.isBlank()) return@launch
        resetAuto()
        _state.update { it.copy(autoBusy = true, error = "") }
        val result = studio.autoCreate(st.studioUrl, st.studioQuery) { step ->
            _state.update { it.copy(autoSteps = (it.autoSteps + step).takeLast(12)) }
        }
        val build = result.build
        _state.update {
            it.copy(
                autoBusy = false,
                autoDone = true,
                autoHealthy = result.healthy,
                autoSiteName = build?.siteName.orEmpty(),
                autoAdapterId = result.adapterId,
                autoSamples = build?.samples.orEmpty().take(6).map { s -> StudioSample(s.title, s.thumbnailUrl, s.durationText) },
                autoSearchCount = result.searchCount,
                autoPlaybackReady = result.playbackReady,
                autoError = if (result.success && result.healthy) "" else result.message.ifBlank { result.errorCode },
                studioJson = result.json.ifBlank { it.studioJson },
                studioTestStatus = result.testStatus,
                studioTestDetails = result.testDetails,
                studioMessage = result.message,
            )
        }
    }

    fun installAuto(onDone: () -> Unit = {}) = viewModelScope.launch {
        val json = _state.value.studioJson
        if (json.isBlank()) return@launch
        _state.update { it.copy(autoBusy = true) }
        val saved = studio.installAndEnable(json)
        if (saved.success && saved.adapterId.isNotBlank()) runCatching { studioBackup.backup(saved.adapterId) }
        _state.update {
            it.copy(
                autoBusy = false,
                autoInstalled = saved.success,
                autoError = if (saved.success) "" else "${saved.errorCode}: ${saved.message}",
                studioMessage = saved.message,
            )
        }
        if (saved.success) onDone()
    }
    fun setStudioInstruction(value: String) = _state.update { it.copy(studioInstruction = value) }
    fun setStudioJson(value: String) = _state.update { it.copy(studioJson = value) }

    fun setStudioProvider(provider: StudioAiProvider) {
        studioAi.selectProvider(provider)
        val account = studioAi.status(provider)
        val model = studioAi.selectedModel(provider)
        _state.update {
            it.copy(
                studioProvider = provider,
                studioModel = model,
                studioModels = emptyList(),
                studioModelCatalog = emptyList(),
                studioConnected = account.connected,
                studioAccount = account.accountLabel,
                studioLoginUrl = "",
                studioLoginCode = "",
                studioTestStatus = "",
                studioTestDetails = "",
                error = "",
            )
        }
        if (provider != StudioAiProvider.LOCAL && account.connected) refreshStudioModels()
    }

    fun setStudioModel(model: String) {
        studioAi.selectModel(_state.value.studioProvider, model)
        _state.update { it.copy(studioModel = model) }
    }

    fun beginStudioLogin() = viewModelScope.launch {
        val provider = _state.value.studioProvider
        if (provider == StudioAiProvider.LOCAL) return@launch
        _state.update {
            it.copy(
                studioAuthBusy = true,
                studioLoginUrl = "",
                studioLoginCode = "",
                studioMessage = "Запрашиваю код входа ${provider.title}…",
                error = "",
            )
        }
        val login = runCatching { studioAi.beginLogin(provider) }.getOrElse { error ->
            _state.update {
                it.copy(
                    studioAuthBusy = false,
                    error = "STUDIO_AI_LOGIN: ${error.message}",
                    studioMessage = "",
                )
            }
            return@launch
        }
        _state.update {
            it.copy(
                studioLoginUrl = login.verificationUrl,
                studioLoginCode = login.userCode,
                studioMessage = "Откройте страницу входа и подтвердите доступ. NoodleMobile ожидает подтверждение…",
            )
        }
        val account = runCatching { studioAi.awaitLogin(login) }.getOrElse { error ->
            _state.update {
                it.copy(
                    studioAuthBusy = false,
                    error = "STUDIO_AI_LOGIN: ${error.message}",
                    studioMessage = "",
                )
            }
            return@launch
        }
        _state.update {
            it.copy(
                studioAuthBusy = false,
                studioConnected = true,
                studioAccount = account.accountLabel,
                studioLoginUrl = "",
                studioLoginCode = "",
                studioMessage = "${provider.title} подключён${account.accountLabel.takeIf { label -> label.isNotBlank() }?.let { label -> ": $label" }.orEmpty()}.",
                error = "",
            )
        }
        loadStudioModels(provider)
    }

    fun disconnectStudioAi() {
        val provider = _state.value.studioProvider
        studioAi.disconnect(provider)
        val account = studioAi.status(provider)
        _state.update {
            it.copy(
                studioConnected = account.connected,
                studioAccount = account.accountLabel,
                studioModels = emptyList(),
                studioModelCatalog = emptyList(),
                studioModel = "",
                studioLoginUrl = "",
                studioLoginCode = "",
                studioMessage = if (provider == StudioAiProvider.LOCAL) it.studioMessage else "${provider.title} отключён.",
                error = "",
            )
        }
    }

    fun refreshStudioModels() = viewModelScope.launch {
        loadStudioModels(_state.value.studioProvider)
    }

    private suspend fun loadStudioModels(provider: StudioAiProvider) {
        if (provider == StudioAiProvider.LOCAL) return
        _state.update { it.copy(studioAuthBusy = true, error = "") }
        val catalog = runCatching { studioAi.modelCatalog(provider) }.getOrElse { error ->
            _state.update { it.copy(studioAuthBusy = false, error = "STUDIO_AI_MODELS: ${error.message}") }
            return
        }
        val models = catalog.map(StudioAiModel::id)
        val saved = studioAi.selectedModel(provider)
        val preferred = when (provider) {
            StudioAiProvider.KILO -> catalog.firstOrNull { it.id.equals("kilo-auto/free", true) }
                ?: catalog.firstOrNull { it.id.equals("openrouter/free", true) }
                ?: catalog.firstOrNull { it.isFree }
            StudioAiProvider.CLINE -> catalog.firstOrNull { it.isFree }
            StudioAiProvider.LOCAL -> null
        }
        val selected = saved.takeIf { it in models }
            ?: preferred?.id
            ?: models.firstOrNull().orEmpty()
        if (selected.isNotBlank()) studioAi.selectModel(provider, selected)
        val freeCount = catalog.count { it.isFree }
        _state.update {
            it.copy(
                studioAuthBusy = false,
                studioModels = models,
                studioModelCatalog = catalog,
                studioModel = selected,
                studioConnected = true,
                studioMessage = if (catalog.isNotEmpty()) {
                    "Каталог ${provider.title}: ${catalog.size} моделей${if (freeCount > 0) " • free: $freeCount" else ""}."
                } else {
                    it.studioMessage
                },
            )
        }
    }

    fun analyzeStudio() = viewModelScope.launch {
        _state.update {
            it.copy(
                studioBusy = true,
                studioMessage = "",
                studioTestStatus = "",
                studioTestDetails = "",
                studioAttempts = 0,
                error = "",
            )
        }
        val result = studio.analyze(_state.value.studioUrl, _state.value.studioQuery)
        applyStudioResult(result)
    }

    fun generateStudio() = viewModelScope.launch {
        val state = _state.value
        _state.update {
            it.copy(
                studioBusy = true,
                studioMessage = "Adapter Studio: HTTP Discovery…",
                studioTestStatus = "",
                studioTestDetails = "",
                studioAttempts = 0,
                error = "",
            )
        }

        // Strict compiler chain: deterministic HTTP -> expanded/browser recovery -> AI repairs the
        // exact strongest candidate. The AI stage never restarts discovery from the original URL.
        var result = studio.generate(
            url = state.studioUrl,
            instruction = state.studioInstruction,
            provider = StudioAiProvider.LOCAL,
            model = "",
            probeQuery = state.studioQuery,
        )
        var repairSeed = result

        if (result.testStatus != "healthy" && state.studioUrl.isNotBlank()) {
            _state.update { it.copy(studioMessage = "Adapter Studio: Browser Discovery, выполняю JavaScript и проверяю post-JS DOM…") }
            val browserResult = runCatching { studioBrowser.recover(state.studioUrl, state.studioQuery) }.getOrNull()
            if (browserResult != null) {
                // Even if Browser Discovery is not yet healthy, its JSON/evidence is the strongest
                // repair seed because it already contains known-route and post-JS observations.
                if (browserResult.json.isNotBlank()) repairSeed = browserResult
                val browserBetter = browserResult.testStatus == "healthy" ||
                    (!result.success && browserResult.success) ||
                    (result.testStatus in setOf("discovery_failed", "search_failed", "no_results", "playback_failed") && browserResult.testStatus !in setOf("browser_discovery_failed", "error"))
                if (browserBetter) result = browserResult
            }
        }

        if (result.testStatus != "healthy" && state.studioProvider != StudioAiProvider.LOCAL) {
            _state.update {
                it.copy(
                    studioMessage = "Adapter Studio: локальные уровни не сертифицированы; AI repair получает лучший local/Browser candidate через ${state.studioProvider.title}…",
                )
            }
            val seed = repairSeed.takeIf { it.json.isNotBlank() } ?: result
            val aiResult = studioAiRepair.repair(
                targetUrl = state.studioUrl,
                candidate = seed,
                instruction = state.studioInstruction,
                provider = state.studioProvider,
                model = state.studioModel,
            )
            if (aiResult.testStatus == "healthy") {
                result = aiResult
            } else {
                // Never downgrade the locally selected candidate merely because AI returned a valid
                // but still uncertified JSON. Keep local/browser JSON and surface AI diagnostics.
                result = result.copy(
                    message = aiResult.message.ifBlank { result.message },
                    testDetails = buildString {
                        append(result.testDetails)
                        if (aiResult.testDetails.isNotBlank() && aiResult.testDetails != result.testDetails) {
                            if (isNotEmpty()) append("\n")
                            append(aiResult.testDetails)
                        }
                    }.trim(),
                    attempts = aiResult.attempts,
                    model = aiResult.model,
                )
            }
        }

        if (result.model.isNotBlank()) setStudioModel(result.model)
        applyStudioResult(result)
    }

    fun testStudio() = viewModelScope.launch {
        val state = _state.value
        val json = state.studioJson
        if (json.isBlank()) return@launch
        val aiAudit = state.studioProvider != StudioAiProvider.LOCAL &&
            state.studioConnected && state.studioModel.isNotBlank() && state.studioUrl.isNotBlank()
        _state.update {
            it.copy(
                studioBusy = true,
                studioMessage = if (aiAudit) {
                    "Live test: локальная проверка + дополнительный AI-аудит маршрутов…"
                } else {
                    "Выполняется локальный live test…"
                },
                error = "",
            )
        }
        val result = if (aiAudit) {
            studio.auditRoutes(
                url = state.studioUrl,
                json = json,
                instruction = state.studioInstruction,
                provider = state.studioProvider,
                model = state.studioModel,
            )
        } else {
            studio.testJson(json)
        }
        if (result.model.isNotBlank()) setStudioModel(result.model)
        applyStudioResult(result, preserveJsonOnFailure = true)
    }

    private fun applyStudioResult(
        result: pro.noodle.mobile.core.studio.StudioAnalyzeResult,
        preserveJsonOnFailure: Boolean = false,
    ) {
        _state.update {
            it.copy(
                studioBusy = false,
                studioJson = when {
                    result.json.isNotBlank() -> result.json
                    preserveJsonOnFailure -> it.studioJson
                    result.success -> it.studioJson
                    else -> it.studioJson
                },
                studioMessage = if (result.success) result.message else "",
                studioTestStatus = result.testStatus,
                studioTestDetails = result.testDetails,
                studioAttempts = result.attempts,
                error = if (result.success) "" else "${result.errorCode}: ${result.message}",
            )
        }
    }

    fun saveStudio(onDone: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(studioBusy = true, error = "") }
        val result = studio.saveJson(_state.value.studioJson)
        val backup = if (result.success && result.adapterId.isNotBlank()) {
            runCatching { studioBackup.backup(result.adapterId) }.getOrNull()
        } else null
        _state.update {
            it.copy(
                studioBusy = false,
                studioMessage = if (result.success) {
                    result.message + backup?.let { file -> " Автобэкап: ${file.name}." }.orEmpty()
                } else it.studioMessage,
                error = if (result.success) "" else "${result.errorCode}: ${result.message}",
            )
        }
        if (result.success) onDone()
    }
}
