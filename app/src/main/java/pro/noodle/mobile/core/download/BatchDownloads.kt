package pro.noodle.mobile.core.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.settings.AppSettingsRepository

data class BatchState(
    val running: Boolean = false,
    val total: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0,
    val message: String = "",
)

/**
 * Пакетная загрузка: пользователь отмечает галочками много роликов (режим включается в настройках),
 * затем одной кнопкой ставит все в очередь. Для каждого ролика фоном определяется лучший поток
 * (ограничение параллельных определений — 3), создаётся файл в выбранной папке и задание в
 * [DownloadRepository]; число одновременных загрузок ограничивает настройка «Параллельных загрузок».
 */
object BatchDownloads {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _selected = MutableStateFlow<Map<String, VideoItem>>(emptyMap())
    val selected: StateFlow<Map<String, VideoItem>> = _selected.asStateFlow()
    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()
    private val _folder = MutableStateFlow("")
    val folder: StateFlow<String> = _folder.asStateFlow()
    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        scope.launch {
            AppSettingsRepository(app).settings.collect { s ->
                _selectMode.value = s.downloadSelectMode
                _folder.value = s.downloadFolderUri
                if (!s.downloadSelectMode && _selected.value.isNotEmpty()) _selected.value = emptyMap()
            }
        }
    }

    fun keyOf(v: VideoItem) = v.id.ifBlank { v.favoriteKey.ifBlank { v.watchUrl } }
    fun toggle(v: VideoItem) = _selected.update { m -> val k = keyOf(v); if (k in m) m - k else m + (k to v) }
    fun clear() { _selected.value = emptyMap() }
    fun dismissMessage() = _state.update { if (it.running) it else BatchState() }

    suspend fun setFolder(uri: Uri) = AppSettingsRepository(app).setDownloadFolderUri(uri.toString())

    /** Ставит выбранные ролики в очередь. Возвращает false, если не выбрана папка. */
    fun startSelected(): Boolean {
        val tree = _folder.value
        val items = _selected.value.values.toList()
        if (items.isEmpty() || _state.value.running) return true
        if (tree.isBlank()) return false
        _selected.value = emptyMap()
        _state.value = BatchState(running = true, total = items.size, message = "Определяю потоки…")
        scope.launch {
            val http = pro.noodle.mobile.core.network.SecureHttpClient()
            val search = BundledSearchRepository(app, http)
            val playback = PlaybackRepository(app, http)
            val repo = DownloadRepository(app)
            val gate = Semaphore(3)
            val errors = mutableListOf<String>()
            coroutineScope {
                items.map { v ->
                    async {
                        gate.withPermit {
                            val ok = runCatching { enqueueOne(v, tree, search, playback, repo) }.getOrElse { it.message ?: it.javaClass.simpleName }
                            _state.update { st ->
                                if (ok == null) st.copy(queued = st.queued + 1) else st.copy(failed = st.failed + 1)
                            }
                            if (ok != null) synchronized(errors) { errors += "${v.title.take(40)}: $ok" }
                        }
                    }
                }.awaitAll()
            }
            _state.update {
                it.copy(running = false, message = "В очереди: ${it.queued} из ${it.total}" + if (it.failed > 0) ", не удалось: ${it.failed}" else "")
            }
        }
        return true
    }

    private fun isAdaptive(s: MediaSource) = s.protocol.equals("hls", true) || s.protocol.equals("dash", true) ||
        s.url.contains(".m3u8", true) || s.url.contains(".mpd", true)

    /** null — успех, иначе текст ошибки. */
    private suspend fun enqueueOne(v: VideoItem, tree: String, search: BundledSearchRepository, playback: PlaybackRepository, repo: DownloadRepository): String? {
        val adapter = search.adapterFor(v) ?: return "источник не найден"
        val r = playback.resolve(v, adapter)
        val session = r.session
        if (!r.success || session == null) return r.errorMessage.ifBlank { r.errorCode.ifBlank { "не открылось" } }
        val sources = session.video.sources
        val first = sources.firstOrNull() ?: return "нет потока"
        var asFile = false
        val chosen = if (isAdaptive(first)) {
            sources.firstOrNull { !isAdaptive(it) && (Regex("(\\d{3,4})p").find(it.label)?.groupValues?.get(1)?.toIntOrNull() ?: 720) >= 480 }
                ?: first.also { asFile = it.protocol.equals("hls", true) || it.url.contains(".m3u8", true) }
        } else first
        if (isAdaptive(chosen) && !asFile) return "формат DASH не поддерживается для пакетной загрузки"
        val title = session.video.title.ifBlank { v.title }
        val uri = createFile(tree, fileName(title, chosen.url, asFile)) ?: return "не удалось создать файл в папке"
        repo.enqueue(
            title, chosen, session.networkPolicy, session.sourceKey,
            destinationUri = uri.toString(),
            thumbnailUrl = session.video.thumbnailUrl.ifBlank { v.thumbnailUrl },
            referer = session.referer,
            asFile = asFile,
        )
        return null
    }

    private fun createFile(tree: String, name: String): Uri? = runCatching {
        val t = Uri.parse(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        DocumentsContract.createDocument(app.contentResolver, parent, "video/*", name)
    }.getOrNull()

    private fun fileName(title: String, url: String, asFile: Boolean): String {
        val ext = if (asFile) "mp4" else Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "mp4")
            .take(8).lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "mp4"
        val base = title.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().take(80).ifBlank { "video" }
        return "$base.$ext"
    }
}

/** Состояние режима выбора для Compose. */
@Composable
fun rememberSelectMode(): Boolean { val m by BatchDownloads.selectMode.collectAsState(); return m }

@Composable
fun rememberIsSelected(v: VideoItem): Boolean {
    val sel by BatchDownloads.selected.collectAsState()
    return BatchDownloads.keyOf(v) in sel
}
