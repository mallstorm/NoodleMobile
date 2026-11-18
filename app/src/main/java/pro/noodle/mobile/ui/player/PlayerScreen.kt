package pro.noodle.mobile.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.FilterChip
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import pro.noodle.mobile.core.download.AdaptiveDownloadSelection
import pro.noodle.mobile.core.download.DownloadRepository
import pro.noodle.mobile.core.download.MediaDownloadRuntime
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.model.VideoItem
import pro.noodle.mobile.core.network.SecureHttpClient
import pro.noodle.mobile.core.player.PlaybackRepository
import pro.noodle.mobile.core.player.PlaybackSession
import pro.noodle.mobile.core.player.PlaybackLogStore
import pro.noodle.mobile.core.player.StreamRuleStore
import pro.noodle.mobile.core.search.BundledSearchRepository
import pro.noodle.mobile.core.settings.AppSettings
import pro.noodle.mobile.core.settings.AppSettingsRepository
import pro.noodle.mobile.core.source.SourceRuntimeDiagnosticsStore
import pro.noodle.mobile.core.storage.UserMediaRepository

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(session: PlaybackSession, streamingClient: OkHttpClient, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context.findActivity()
    var activeSession by remember(session.sourceKey, session.video.id, session.video.watchUrl) { mutableStateOf(session) }
    val video = activeSession.video
    val key = video.favoriteKey.ifBlank { video.id.ifBlank { video.watchUrl } }
    val userRepo = remember { UserMediaRepository(context.applicationContext) }
    val downloadRepo = remember { DownloadRepository(context.applicationContext) }
    val settingsRepo = remember { AppSettingsRepository(context.applicationContext) }
    val resolverHttp = remember { SecureHttpClient() }
    val playbackRepo = remember { PlaybackRepository(context.applicationContext, resolverHttp) }
    val searchRepo = remember { BundledSearchRepository(context.applicationContext, resolverHttp) }
    val runtimeDiagnostics = remember { SourceRuntimeDiagnosticsStore(context.applicationContext) }
    val appSettings by remember { settingsRepo.settings }.collectAsState(initial = AppSettings())
    val ioScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val isFavorite by remember(key) { userRepo.isFavorite(key) }.collectAsState(initial = false)
    val isFollowed = activeSession.sourceKey in appSettings.followedSources

    DisposableEffect(Unit) {
        onDispose { ioScope.cancel() }
    }

    var message by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
    var selectedVariant by remember(video.id) { mutableIntStateOf(-1) }
    var selectedAudio by remember(video.id) { mutableIntStateOf(-1) }
    var selectedText by remember(video.id) { mutableIntStateOf(-1) }
    var pendingDownload by remember { mutableStateOf<MediaSource?>(null) }
    var pendingSelection by remember { mutableStateOf<AdaptiveDownloadSelection?>(null) }
    var pendingAsFile by remember { mutableStateOf(false) }
    var showAdaptiveDownloadDialog by remember { mutableStateOf(false) }
    var failedSourceUrls by remember(video.id) { mutableStateOf<Set<String>>(emptySet()) }
    var resolverRecoveryAttempts by remember(video.id) { mutableIntStateOf(0) }
    var handoffPositionMs by remember(video.id) { mutableLongStateOf(0L) }
    var showStreamReport by remember { mutableStateOf(false) }
    var networkRetries by remember(video.id) { mutableIntStateOf(0) }
    var attemptStartMs by remember { mutableLongStateOf(0L) }
    var liveDurationMs by remember(video.id) { mutableLongStateOf(0L) }
    val ruleStore = remember { StreamRuleStore(context.applicationContext) }
    val logStore = remember { PlaybackLogStore(context.applicationContext) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val src = pendingDownload
        val selection = pendingSelection
        val asFile = pendingAsFile
        pendingDownload = null
        pendingSelection = null
        pendingAsFile = false
        if (uri != null && src != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            ioScope.launch {
                downloadRepo.enqueue(
                    video.title,
                    src,
                    activeSession.networkPolicy,
                    activeSession.sourceKey,
                    uri.toString(),
                    video.thumbnailUrl,
                    adaptiveSelection = selection ?: AdaptiveDownloadSelection(),
                    referer = activeSession.referer,
                    asFile = asFile,
                )
                withContext(Dispatchers.Main) { message = "Добавлено в загрузки" }
            }
        }
    }

    val initial = video.sources.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
    var selectedIndex by remember(video.id) {
        mutableIntStateOf(initial.coerceIn(0, (video.sources.size - 1).coerceAtLeast(0)))
    }
    val source = video.sources.getOrNull(selectedIndex)
    if (source == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Button(onClick = onBack) { Text("Назад") }
            Text("Воспроизводимый источник отсутствует.")
        }
        return
    }

    val player = remember(video.id, streamingClient, activeSession.sourceKey, source.url) {
        val factory = MediaDownloadRuntime.cacheDataSourceFactory(
            context.applicationContext,
            activeSession.networkPolicy,
            activeSession.sourceKey,
            activeSession.referer,
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5_000,
                30_000,
                750,
                1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    var restoredPosition by remember(video.id) { mutableLongStateOf(0L) }
    // The saved position is read BEFORE the first prepare(), so the stream is loaded once and
    // starts exactly where the user left off (previously it loaded from 0 and then re-prepared).
    var positionReady by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(video.id, appSettings.rememberPlaybackPosition) {
        restoredPosition = if (appSettings.rememberPlaybackPosition) {
            withContext(Dispatchers.IO) { userRepo.playbackPosition(key)?.positionMs ?: 0L }
        } else 0L
        positionReady = true
        if (restoredPosition > 5_000L) message = "Продолжаем с ${formatClock(restoredPosition)}"
    }

    // Persist progress periodically so it survives process death, and keep History progress bars real.
    LaunchedEffect(player, appSettings.rememberPlaybackPosition) {
        if (!appSettings.rememberPlaybackPosition) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10_000)
            if (!player.isPlaying) continue
            val pos = player.currentPosition.coerceAtLeast(0)
            val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            ioScope.launch { runCatching { userRepo.savePlaybackPosition(key, pos, dur); userRepo.saveHistory(video, pos, dur) } }
        }
    }

    fun mediaItemFor(s: MediaSource): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(s.url))
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(video.title).setArtist(video.sourceName).build())
        if (s.mimeType.isNotBlank()) builder.setMimeType(s.mimeType)
        val subtitles = video.textTracks.map { t ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                .setMimeType(guessSubtitleMime(t.url))
                .apply {
                    if (t.language.isNotBlank()) setLanguage(t.language)
                    if (t.label.isNotBlank()) setLabel(t.label)
                    setSelectionFlags(if (t.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                }
                .build()
        }
        if (subtitles.isNotEmpty()) builder.setSubtitleConfigurations(subtitles)
        return builder.build()
    }

    LaunchedEffect(player, source.url, positionReady) {
        if (!positionReady) return@LaunchedEffect
        val firstLoad = player.mediaItemCount == 0
        val old = handoffPositionMs.takeIf { it > 0L }
            ?: player.currentPosition.takeIf { it > 0 }
            ?: restoredPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(mediaItemFor(source), old.coerceAtLeast(0))
        attemptStartMs = android.os.SystemClock.elapsedRealtime()
        player.prepare()
        player.playWhenReady = firstLoad || wasPlaying || old == 0L
        handoffPositionMs = 0L
    }

    DisposableEffect(fullscreen, activity) {
        activity?.window?.let { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                activity?.window?.let { window ->
                    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            liveDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: liveDurationMs
            kotlinx.coroutines.delay(1_000)
        }
    }

    fun humanError(error: PlaybackException): String {
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "нет связи с сервером видео"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "сервер видео отказал в доступе или поток устарел"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "файл видео не найден"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "файл повреждён или формат не поддерживается"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "устройство не смогло декодировать видео"
            else -> error.message.orEmpty().ifBlank { "ошибка воспроизведения" }
        }
        return "Не удалось воспроизвести: $reason (${error.errorCodeName})"
    }

    fun reResolve(exhaustive: Boolean, why: String) {
        message = why
        ioScope.launch {
            val adapter = searchRepo.adapterFor(video)
            if (adapter == null) {
                withContext(Dispatchers.Main) { message = "SNP источника не найден для повторного разрешения." }
                return@launch
            }
            val fresh = playbackRepo.resolve(video.copy(sources = emptyList()), adapter, exhaustive)
            withContext(Dispatchers.Main) {
                if (fresh.success && fresh.session != null && fresh.session.video.sources.isNotEmpty()) {
                    handoffPositionMs = player.currentPosition.coerceAtLeast(handoffPositionMs)
                    activeSession = fresh.session
                    selectedIndex = fresh.session.video.sources.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
                    failedSourceUrls = emptySet()
                    message = "Поток обновлён, продолжаю."
                } else {
                    message = "Не удалось найти другой поток: ${fresh.errorCode} ${fresh.errorMessage}".trim()
                }
            }
        }
    }

    /** «Это превью / не то видео»: запоминаем правило в адаптере и без ошибок переключаемся на следующий поток. */
    fun rejectCurrentStream() {
        val badUrl = source.url
        val pattern = ruleStore.addReject(activeSession.sourceKey, badUrl)
        logStore.add(activeSession.sourceKey, video.title, badUrl, -1.0, "помечено как «не то видео»" + if (pattern != null) " → правило добавлено" else "")
        runtimeDiagnostics.recordPlayback(activeSession.sourceKey, video.watchUrl, badUrl, "user_rejected", "USER_REJECTED_STREAM")
        handoffPositionMs = player.currentPosition.coerceAtLeast(0L)
        val failed = failedSourceUrls + badUrl
        failedSourceUrls = failed
        val next = video.sources.indices.firstOrNull { it != selectedIndex && video.sources[it].url !in failed }
        if (next != null) {
            message = "Поток помечен как не то видео, пробую другой…"
            selectedIndex = next
        } else {
            reResolve(true, "Поток помечен как не то видео, ищу другой глубже…")
        }
    }

    DisposableEffect(player, source.url, selectedIndex) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handoffPositionMs = player.currentPosition.coerceAtLeast(0L)
                logStore.add(activeSession.sourceKey, video.title, source.url, -1.0, "ошибка ${error.errorCodeName}")
                val isNetwork = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                if (isNetwork && networkRetries < 3) {
                    networkRetries++
                    message = "Связь прервалась, повторяю ($networkRetries/3)…"
                    val resumeAt = handoffPositionMs
                    ioScope.launch {
                        kotlinx.coroutines.delay(1_500L * networkRetries)
                        withContext(Dispatchers.Main) {
                            attemptStartMs = android.os.SystemClock.elapsedRealtime()
                            player.seekTo(resumeAt)
                            player.prepare()
                            player.play()
                        }
                    }
                    return
                }
                runtimeDiagnostics.recordPlayback(
                    activeSession.sourceKey,
                    video.watchUrl,
                    source.url,
                    "media3_error",
                    error.errorCodeName,
                )
                val newlyFailed = failedSourceUrls + source.url
                failedSourceUrls = newlyFailed
                val next = video.sources.indices.firstOrNull { index ->
                    index != selectedIndex && video.sources[index].url !in newlyFailed
                }
                if (next != null) {
                    message = "Переключаюсь на другой поток (${next + 1}/${video.sources.size})…"
                    selectedIndex = next
                    return
                }

                if (resolverRecoveryAttempts < 1) {
                    resolverRecoveryAttempts++
                    message = "Автовосстановление: все найденные потоки отклонены, повторно разрешаю страницу видео…"
                    ioScope.launch {
                        val adapter = searchRepo.adapterFor(video)
                        if (adapter == null) {
                            withContext(Dispatchers.Main) {
                                message = "SNP источника не найден для повторного разрешения."
                            }
                            return@launch
                        }
                        val fresh = playbackRepo.resolve(video.copy(sources = emptyList()), adapter)
                        withContext(Dispatchers.Main) {
                            if (fresh.success && fresh.session != null && fresh.session.video.sources.isNotEmpty()) {
                                activeSession = fresh.session
                                val freshDefault = fresh.session.video.sources.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
                                selectedIndex = freshDefault
                                failedSourceUrls = emptySet()
                                message = "Потоки обновлены, воспроизведение восстановлено."
                            } else {
                                message = humanError(error)
                            }
                        }
                    }
                    return
                }

                message = humanError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    networkRetries = 0
                    if (message.startsWith("Связь прервалась") || message.startsWith("Переключаюсь")) message = ""
                    liveDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: liveDurationMs
                    // A valid HTTP stream may still be a five-second hover preview.
                    // Prefer another candidate instead of declaring playback successful.
                    if (EngineParsing.isPreviewDuration(video.duration, player.duration)) {
                        runtimeDiagnostics.recordPlayback(
                            activeSession.sourceKey, video.watchUrl, source.url,
                            "media3_preview", "MEDIA3_PREVIEW_ONLY",
                        )
                        logStore.add(activeSession.sourceKey, video.title, source.url, -1.0, "превью ${formatClock(player.duration.coerceAtLeast(0L))} — отброшено")
                        val rejected = failedSourceUrls + source.url
                        failedSourceUrls = rejected
                        val next = video.sources.indices.firstOrNull { index ->
                            index != selectedIndex && video.sources[index].url !in rejected
                        }
                        if (next != null) {
                            message = "Обнаружено короткое превью. Переключаюсь на основной поток…"
                            selectedIndex = next
                        } else {
                            message = "Найдено только короткое превью. Полный видеопоток не подтверждён."
                        }
                        return
                    }
                    runtimeDiagnostics.recordPlayback(activeSession.sourceKey, video.watchUrl, source.url, "media3_ready")
                    logStore.add(
                        activeSession.sourceKey, video.title, source.url,
                        (android.os.SystemClock.elapsedRealtime() - attemptStartMs).coerceAtLeast(0L) / 1000.0,
                        "играет, ${formatClock(player.duration.coerceAtLeast(0L))}, вердикт ${activeSession.streamVerdict.ifBlank { "?" }}",
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            val pos = player.currentPosition.coerceAtLeast(0)
            val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            player.release()
            if (appSettings.rememberPlaybackPosition) {
                ioScope.launch { runCatching { userRepo.savePlaybackPosition(key, pos, dur); userRepo.saveHistory(video, pos, dur) } }
            }
        }
    }

    fun isAdaptiveSource(src: MediaSource) = src.protocol.equals("hls", true) ||
        src.protocol.equals("dash", true) ||
        src.url.contains(".m3u8", true) ||
        src.url.contains(".mpd", true)

    fun requestDownload() {
        // A full-length MP4 next to the HLS stream downloads directly (one request, resumable).
        val direct = if (isAdaptiveSource(source)) {
            video.sources.firstOrNull { !isAdaptiveSource(it) && (Regex("(\\d{3,4})p").find(it.label)?.groupValues?.get(1)?.toIntOrNull() ?: 720) >= 480 }
        } else null
        when {
            direct != null -> {
                pendingDownload = direct
                pendingSelection = null
                pendingAsFile = false
                message = "Скачиваю напрямую: ${direct.label.ifBlank { "MP4" }}"
                createDocument.launch(safeFileName(video.title, direct.url))
            }
            isAdaptiveSource(source) -> showAdaptiveDownloadDialog = true
            else -> {
                pendingDownload = source
                pendingSelection = null
                pendingAsFile = false
                createDocument.launch(safeFileName(video.title, source.url))
            }
        }
    }

    fun shareVideo() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, video.watchUrl)
        context.startActivity(Intent.createChooser(intent, "Поделиться видео"))
    }

    fun openRelated(candidate: VideoItem) {
        message = ""
        ioScope.launch {
            val adapter = searchRepo.adapterFor(candidate)
            if (adapter == null) {
                withContext(Dispatchers.Main) { message = "SNP источника не найден." }
                return@launch
            }
            userRepo.saveHistory(candidate, 0L, 0L)
            val result = playbackRepo.resolve(candidate, adapter)
            withContext(Dispatchers.Main) {
                if (result.success && result.session != null) {
                    activeSession = result.session
                    fullscreen = false
                    showPlaybackSettings = false
                    failedSourceUrls = emptySet()
                    resolverRecoveryAttempts = 0
                    message = ""
                } else {
                    message = "${result.errorCode}: ${result.errorMessage}"
                }
            }
        }
    }

    if (showStreamReport) {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val reportText = buildString {
            appendLine("Источник: ${video.sourceName} (${activeSession.sourceKey})")
            appendLine("Видео: ${video.title}")
            appendLine("Страница: ${video.watchUrl}")
            appendLine("Поток: ${source.url}")
            appendLine("Вердикт: ${activeSession.streamVerdict.ifBlank { "не определён" }}")
            if (activeSession.expectedSeconds > 0) appendLine("Ожидалось: ${formatClock(activeSession.expectedSeconds * 1000L)}")
            appendLine("Реально: ${formatClock(liveDurationMs)}")
            if (activeSession.streamNote.isNotBlank()) appendLine("Проверка: ${activeSession.streamNote}")
            if (activeSession.streamDetails.isNotBlank()) { appendLine(); appendLine(activeSession.streamDetails) }
            val rules = ruleStore.rules(activeSession.sourceKey)
            if (rules.isNotEmpty()) { appendLine(); appendLine("Правила «не то видео»:"); rules.forEach { appendLine("  $it") } }
            val log = logStore.dump(activeSession.sourceKey)
            if (log.isNotBlank()) { appendLine(); appendLine("Журнал:"); appendLine(log) }
        }
        AlertDialog(
            onDismissRequest = { showStreamReport = false },
            title = { Text("Какой поток играет") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        reportText,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStreamReport = false; rejectCurrentStream() }) { Text("Это превью / не то видео") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(reportText)) }) { Text("Копировать") }
                    TextButton(onClick = { showStreamReport = false }) { Text("Закрыть") }
                }
            },
        )
    }

    if (showAdaptiveDownloadDialog) {
        AdaptiveDownloadDialog(
            video = video,
            initialVariant = selectedVariant,
            initialAudio = selectedAudio,
            initialText = selectedText,
            onDismiss = { showAdaptiveDownloadDialog = false },
            canSaveAsFile = source.protocol.equals("hls", true) || source.url.contains(".m3u8", true),
            onConfirm = { selection, asFile ->
                showAdaptiveDownloadDialog = false
                if (asFile) {
                    pendingDownload = source
                    pendingSelection = selection
                    pendingAsFile = true
                    createDocument.launch(safeFileName(video.title, "video.mp4"))
                    return@AdaptiveDownloadDialog
                }
                ioScope.launch {
                    downloadRepo.enqueue(
                        video.title,
                        source,
                        activeSession.networkPolicy,
                        activeSession.sourceKey,
                        thumbnailUrl = video.thumbnailUrl,
                        adaptiveSelection = selection,
                        referer = activeSession.referer,
                    )
                    withContext(Dispatchers.Main) {
                        message = "Офлайн: ${selection.summary()}"
                    }
                }
            },
        )
    }

    if (showPlaybackSettings) {
        PlayerSettingsSheet(
            video = video,
            player = player,
            selectedSource = selectedIndex,
            selectedVariant = selectedVariant,
            selectedAudio = selectedAudio,
            selectedText = selectedText,
            onSource = {
                failedSourceUrls = failedSourceUrls - video.sources.getOrNull(it)?.url.orEmpty()
                selectedIndex = it
            },
            onVariant = { idx ->
                selectedVariant = idx
                val builder = player.trackSelectionParameters.buildUpon()
                if (idx < 0) builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                else {
                    val v = video.variants[idx]
                    if (v.width > 0 && v.height > 0) builder.setMaxVideoSize(v.width, v.height)
                }
                player.trackSelectionParameters = builder.build()
            },
            onAudio = { idx ->
                selectedAudio = idx
                val builder = player.trackSelectionParameters.buildUpon()
                if (idx >= 0) builder.setPreferredAudioLanguage(video.audioTracks[idx].language.ifBlank { null })
                else builder.setPreferredAudioLanguage(null)
                player.trackSelectionParameters = builder.build()
            },
            onText = { idx ->
                selectedText = idx
                val builder = player.trackSelectionParameters.buildUpon()
                when {
                    idx == -2 -> {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        builder.setPreferredTextLanguage(null)
                    }
                    idx < 0 -> {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        builder.setPreferredTextLanguage(null)
                    }
                    else -> {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        builder.setPreferredTextLanguage(video.textTracks[idx].language.ifBlank { null })
                    }
                }
                player.trackSelectionParameters = builder.build()
            },
            onDismiss = { showPlaybackSettings = false },
        )
    }

    val backFromPlayer = {
        if (fullscreen) fullscreen = false else onBack()
    }

    if (fullscreen) {
        PlayerVideoSurface(
            player = player,
            fullscreen = true,
            onBack = backFromPlayer,
            onToggleFullscreen = { fullscreen = false },
            onOpenSettings = { showPlaybackSettings = true },
            activity = activity,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        PlayerVideoSurface(
            player = player,
            fullscreen = false,
            onBack = backFromPlayer,
            onToggleFullscreen = { fullscreen = true },
            onOpenSettings = { showPlaybackSettings = true },
            activity = activity,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    video.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(
                        video.views.takeIf { it.isNotBlank() },
                        video.duration.takeIf { it.isNotBlank() },
                        source.label.takeIf { it.isNotBlank() },
                    ).filterNotNull().joinToString(" • ").ifBlank { video.sourceName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                StreamBadge(
                    verdict = activeSession.streamVerdict,
                    durationMs = liveDurationMs,
                    format = formatLabel(source),
                    size = Regex("""\d[\d.,]*\s?[КкМмГгKkMmGg][БбBb]""").find(activeSession.streamNote)?.value.orEmpty(),
                    onClick = { showStreamReport = true },
                    onReject = { rejectCurrentStream() },
                )
            }

            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pro.noodle.mobile.ui.SourceIcon(video.sourceBaseUrl.ifBlank { "https://" + activeSession.sourceKey }, video.sourceName.ifBlank { activeSession.sourceKey }, 44.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.sourceName.ifBlank { activeSession.sourceKey }, fontWeight = FontWeight.SemiBold)
                            Text(activeSession.sourceKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(
                            onClick = {
                                ioScope.launch { settingsRepo.setSourceFollowed(activeSession.sourceKey, !isFollowed) }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isFollowed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Icon(if (isFollowed) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isFollowed) "Вы подписаны" else "Подписаться")
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerActionButton(
                        icon = if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (isFavorite) "В избранном" else "В избранное",
                        emphasized = true,
                    ) {
                        ioScope.launch {
                            if (isFavorite) userRepo.removeFavorite(video) else userRepo.addFavorite(video)
                        }
                    }
                    PlayerActionButton(Icons.Outlined.Download, "Скачать") { requestDownload() }
                    PlayerActionButton(Icons.Outlined.Share, "Поделиться") { shareVideo() }
                    PlayerActionButton(Icons.Outlined.MoreHoriz, "Ещё") { showPlaybackSettings = true }
                }
            }

            if (message.isNotBlank()) {
                item {
                    PlaybackMessageCard(
                        message = message,
                        onRetry = {
                            message = ""
                            failedSourceUrls = emptySet()
                            resolverRecoveryAttempts = 0
                            player.prepare()
                            player.play()
                        },
                    )
                }
            }

            item {
                RelatedVideosSection(
                    currentVideo = video,
                    columns = appSettings.relatedVideosColumns,
                    onColumnsChange = { columns ->
                        ioScope.launch { settingsRepo.setRelatedVideosColumns(columns) }
                    },
                    onOpen = ::openRelated,
                )
            }
        }
    }
}

private fun formatLabel(src: MediaSource): String = when {
    src.protocol.equals("hls", true) || src.url.contains(".m3u8", true) -> "HLS"
    src.protocol.equals("dash", true) || src.url.contains(".mpd", true) -> "DASH"
    src.url.contains(".webm", true) || src.mimeType.contains("webm", true) -> "WEBM"
    else -> "MP4"
}

@Composable
private fun StreamBadge(
    verdict: String,
    durationMs: Long,
    format: String,
    size: String,
    onClick: () -> Unit,
    onReject: () -> Unit,
) {
    val (label, container, content) = when (verdict.lowercase()) {
        "full" -> Triple("Полное", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        "preview" -> Triple("Превью", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        else -> Triple("Не проверено", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = container, contentColor = content, modifier = Modifier.weight(1f, fill = false)) {
            Text(
                listOfNotNull(label, durationMs.takeIf { it > 0L }?.let(::formatClock), format, size.takeIf { it.isNotBlank() }).joinToString(" · "),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onReject) { Text("Это превью / не то") }
    }
}

@Composable
private fun PlaybackMessageCard(message: String, onRetry: () -> Unit) {
    var details by remember(message) { mutableStateOf(false) }
    val isError = message.contains("ERROR", true) || message.contains("FAILED", true) || message.contains("HTTP", true)
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (isError) "Не удалось воспроизвести видео" else message, fontWeight = FontWeight.SemiBold)
            if (isError) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry) { Text("Повторить") }
                    TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть детали" else "Подробности") }
                }
                if (details) Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    if (emphasized) {
        Button(onClick = onClick) {
            Icon(icon, null)
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    } else {
        FilledTonalButton(onClick = onClick) {
            Icon(icon, null)
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
        options.forEachIndexed { index, text ->
            FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(text) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    video: VideoItem,
    player: Player,
    selectedSource: Int,
    selectedVariant: Int,
    selectedAudio: Int,
    selectedText: Int,
    onSource: (Int) -> Unit,
    onVariant: (Int) -> Unit,
    onAudio: (Int) -> Unit,
    onText: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Настройки воспроизведения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (video.sources.size > 1) {
                ChoiceRow(
                    "Источник",
                    video.sources.mapIndexed { index, s -> s.label.ifBlank { s.protocol.ifBlank { "Источник ${index + 1}" } } },
                    selectedSource,
                    onSource,
                )
            }
            if (video.variants.isNotEmpty()) {
                ChoiceRow(
                    "Качество",
                    listOf("Auto") + video.variants.map { it.label.ifBlank { if (it.height > 0) "${it.height}p" else it.id } },
                    selectedVariant + 1,
                ) { onVariant(it - 1) }
            }
            if (video.audioTracks.isNotEmpty()) {
                ChoiceRow(
                    "Аудио",
                    listOf("Auto") + video.audioTracks.map { it.label.ifBlank { it.language.ifBlank { "Track" } } },
                    selectedAudio + 1,
                ) { onAudio(it - 1) }
            }
            if (video.textTracks.isNotEmpty()) {
                ChoiceRow(
                    "Субтитры",
                    listOf("Выкл", "Auto") + video.textTracks.map { it.label.ifBlank { it.language.ifBlank { "Track" } } },
                    selectedText + 2,
                ) { idx ->
                    when (idx) {
                        0 -> onText(-2)
                        1 -> onText(-1)
                        else -> onText(idx - 2)
                    }
                }
            }

            Text("Скорость", fontWeight = FontWeight.SemiBold)
            var currentSpeed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(currentSpeed - speed) < 0.01f,
                        onClick = { player.setPlaybackSpeed(speed); currentSpeed = speed },
                        label = { Text(if (speed == 1f) "1×" else "${speed.toString().trimEnd('0').trimEnd('.')}×") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun safeFileName(title: String, url: String): String {
    val ext = Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "mp4")
        .take(8).lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "mp4"
    val base = title.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().take(80).ifBlank { "video" }
    return "$base.$ext"
}

private fun guessSubtitleMime(url: String) = when {
    url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
    url.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
    url.endsWith(".ttml", true) || url.endsWith(".xml", true) -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.TEXT_VTT
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun AdaptiveDownloadDialog(
    video: VideoItem,
    initialVariant: Int,
    initialAudio: Int,
    initialText: Int,
    onDismiss: () -> Unit,
    canSaveAsFile: Boolean,
    onConfirm: (AdaptiveDownloadSelection, Boolean) -> Unit,
) {
    var asFile by remember { mutableIntStateOf(if (canSaveAsFile) 0 else 1) }
    var variant by remember { mutableIntStateOf(initialVariant) }
    var audio by remember { mutableIntStateOf(initialAudio) }
    var text by remember { mutableIntStateOf(initialText) }
    val qualityNames = listOf("Auto") + video.variants.map { it.label.ifBlank { if (it.height > 0) "${it.height}p" else it.id } }
    val audioNames = listOf("Auto") + video.audioTracks.map { it.label.ifBlank { it.language.ifBlank { "Track" } } }
    val textNames = listOf("Без субтитров", "Auto") + video.textTracks.map { it.label.ifBlank { it.language.ifBlank { "Track" } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скачать видео") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canSaveAsFile) {
                    ChoiceRow("Куда", listOf("Файл MP4 в памяти телефона", "Офлайн внутри приложения"), asFile) { asFile = it }
                } else {
                    Text("Media3 скачает только выбранные adaptive-дорожки.", style = MaterialTheme.typography.bodySmall)
                }
                ChoiceRow("Качество", qualityNames, variant + 1) { variant = it - 1 }
                if (video.audioTracks.isNotEmpty()) ChoiceRow("Аудио", audioNames, audio + 1) { audio = it - 1 }
                if (video.textTracks.isNotEmpty()) ChoiceRow("Субтитры", textNames, text + 1) { text = it - 1 }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = video.variants.getOrNull(variant)
                val a = video.audioTracks.getOrNull(audio)
                val textOption = text + 1
                val t = if (textOption >= 2) video.textTracks.getOrNull(textOption - 2) else null
                onConfirm(
                    AdaptiveDownloadSelection(
                        maxVideoWidth = v?.width?.takeIf { it > 0 } ?: Int.MAX_VALUE,
                        maxVideoHeight = v?.height?.takeIf { it > 0 } ?: Int.MAX_VALUE,
                        videoLabel = v?.label?.ifBlank { if ((v?.height ?: 0) > 0) "${v?.height}p" else "Selected" } ?: "Auto",
                        audioLanguage = a?.language.orEmpty(),
                        textLanguage = t?.language.orEmpty(),
                        includeSubtitles = textOption >= 1,
                    ),
                    canSaveAsFile && asFile == 0,
                )
            }) { Text("Скачать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
