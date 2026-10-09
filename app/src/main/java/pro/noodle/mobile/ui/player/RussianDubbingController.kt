package pro.noodle.mobile.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class DubbingPhase {
    OFF,
    PREPARING,
    ACTIVE,
    ERROR,
}

internal enum class DubbingSource {
    SUBTITLES,
    ASR,
}

internal data class DubbingUiState(
    val enabled: Boolean = false,
    val phase: DubbingPhase = DubbingPhase.OFF,
    val message: String = "RU AI",
    val sourceLanguage: String = "",
    val source: DubbingSource = DubbingSource.SUBTITLES,
    val requiresSetup: Boolean = false,
)

/**
 * Russian AI voice-over synchronized to the Media3 playback clock.
 *
 * Subtitle path remains low-latency live TTS. For video without text tracks the controller uses
 * ASR, translates timed segments and pre-renders Russian speech into a persistent audio cache.
 * A dedicated ExoPlayer then starts the prepared speech file at the exact main-player offset.
 */
@OptIn(UnstableApi::class)
internal class RussianDubbingController(
    context: Context,
    private val player: Player,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val preferences = DubbingPreferences(appContext)
    private val audioExporter = AsrAudioExporter(appContext)
    private val asrClient = OpenAiAsrClient()
    private val transcriptCache = AsrTranscriptCache(appContext)
    private val audioCache = DubbingAudioCache(appContext)
    private val dubbedAudioPlayer = ExoPlayer.Builder(appContext).build()
    private val translationMutex = Mutex()
    private val renderMutex = Mutex()
    private val renderWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private var translator: Translator? = null
    private var translatorSourceLanguage: String = ""
    private var cueJob: Job? = null
    private var asrJob: Job? = null
    private val asrLoadJobs = mutableMapOf<Long, Job>()
    private val asrRenderJobs = mutableMapOf<Long, Job>()
    private val asrChunks = mutableMapOf<Long, List<AsrSegment>>()
    private val preparedAudioFiles = mutableMapOf<String, File>()
    private val preparedChunks = mutableSetOf<Long>()

    private var enabled = false
    private var usingAsr = false
    private var liveTtsReady = false
    private var renderTtsReady = false
    private var closed = false
    private var lastCueText = ""
    private var spokenAsrKey = ""
    private var lastPlayerPositionMs = 0L
    private var originalVolume = 1f
    private var originalTrackParameters: TrackSelectionParameters? = null
    private var activeConfig = preferences.load()
    private var activeMediaKey = ""
    private lateinit var liveTts: TextToSpeech
    private lateinit var renderTts: TextToSpeech

    private val mutableState = MutableStateFlow(DubbingUiState())
    val state: StateFlow<DubbingUiState> = mutableState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            if (!enabled || usingAsr) return
            val text = cueGroup.cues
                .mapNotNull { it.text?.toString() }
                .joinToString(" ")
                .cleanCueText()
            if (text.isNotBlank() && text != lastCueText) processSubtitleCue(text)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                if (usingAsr) {
                    dubbedAudioPlayer.pause()
                    spokenAsrKey = ""
                } else if (this@RussianDubbingController::liveTts.isInitialized) {
                    liveTts.stop()
                }
            } else if (usingAsr) {
                spokenAsrKey = ""
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            dubbedAudioPlayer.playbackParameters = playbackParameters
        }
    }

    init {
        liveTts = TextToSpeech(appContext) { status ->
            scope.launch {
                if (closed) return@launch
                liveTtsReady = configureRussianTts(liveTts, status, setMediaAttributes = true)
                if (liveTtsReady) applyConfiguredVoice("")
                if (enabled && !liveTtsReady && !usingAsr) showTtsUnavailable()
            }
        }
        renderTts = TextToSpeech(appContext) { status ->
            scope.launch {
                if (closed) return@launch
                renderTtsReady = configureRussianTts(renderTts, status, setMediaAttributes = false)
            }
        }
        renderTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { renderWaiters.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                failRender(utteranceId, "RU_TTS_RENDER_FAILED")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                failRender(utteranceId, "RU_TTS_RENDER_FAILED_$errorCode")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { renderWaiters.remove(it)?.cancel() }
            }
        })
        dubbedAudioPlayer.volume = 1f
        player.addListener(listener)
    }

    fun toggle() {
        if (enabled) disable() else enable()
    }

    fun configuration(): DubbingConfig = preferences.load()

    fun hasAsrApiKey(): Boolean = preferences.hasApiKey()

    fun russianVoiceNames(): List<String> {
        if (!this::liveTts.isInitialized || !liveTtsReady) return emptyList()
        return russianVoices(liveTts).map { it.name }.distinct().take(MAX_VOICE_CHOICES)
    }

    fun saveConfiguration(config: DubbingConfig, newApiKey: String? = null) {
        preferences.save(config, newApiKey)
        activeConfig = preferences.load()
        val wasSetupRequired = mutableState.value.requiresSetup
        if (wasSetupRequired && preferences.hasApiKey()) {
            mutableState.value = mutableState.value.copy(requiresSetup = false)
            enable()
            return
        }
        if (enabled) {
            player.volume = targetOriginalVolume(activeConfig.mode)
            if (usingAsr) restartAsrPipeline() else applyConfiguredVoice("")
        }
    }

    private fun configureRussianTts(engine: TextToSpeech, status: Int, setMediaAttributes: Boolean): Boolean {
        if (status != TextToSpeech.SUCCESS) return false
        val result = engine.setLanguage(Locale("ru", "RU"))
        val ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (ready && setMediaAttributes) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        return ready
    }

    private fun failRender(utteranceId: String?, message: String) {
        utteranceId?.let { id ->
            renderWaiters.remove(id)?.completeExceptionally(IllegalStateException(message))
        }
    }

    private fun enable() {
        if (closed || enabled) return
        activeConfig = preferences.load()
        val textGroup = player.currentTracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSupported(it) }
        }
        if (textGroup != null) enableSubtitleMode(textGroup) else enableAsrMode()
    }

    private fun enableSubtitleMode(textGroup: androidx.media3.common.Tracks.Group) {
        val trackIndex = (0 until textGroup.length).firstOrNull { textGroup.isTrackSupported(it) } ?: 0
        rememberPlaybackState()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, trackIndex))
            .build()
        player.volume = targetOriginalVolume(activeConfig.mode)
        enabled = true
        usingAsr = false
        lastCueText = ""
        mutableState.value = DubbingUiState(
            enabled = true,
            phase = DubbingPhase.PREPARING,
            message = "RU AI • ожидаю реплику…",
            source = DubbingSource.SUBTITLES,
        )
    }

    private fun enableAsrMode() {
        if (!preferences.hasApiKey()) {
            mutableState.value = DubbingUiState(
                enabled = false,
                phase = DubbingPhase.ERROR,
                message = "RU AI • настройте ASR API",
                source = DubbingSource.ASR,
                requiresSetup = true,
            )
            return
        }
        rememberPlaybackState()
        player.volume = targetOriginalVolume(activeConfig.mode)
        enabled = true
        usingAsr = true
        spokenAsrKey = ""
        lastPlayerPositionMs = player.currentPosition.coerceAtLeast(0L)
        mutableState.value = DubbingUiState(
            enabled = true,
            phase = DubbingPhase.PREPARING,
            message = "RU AI • готовлю защищённый ASR…",
            source = DubbingSource.ASR,
        )
        startAsr()
    }

    private fun rememberPlaybackState() {
        originalVolume = player.volume
        originalTrackParameters = player.trackSelectionParameters
    }

    private fun restartAsrPipeline() {
        if (!enabled || !usingAsr) return
        asrJob?.cancel()
        asrJob = null
        asrLoadJobs.values.forEach { it.cancel() }
        asrLoadJobs.clear()
        asrRenderJobs.values.forEach { it.cancel() }
        asrRenderJobs.clear()
        renderWaiters.values.forEach { it.cancel() }
        renderWaiters.clear()
        asrChunks.clear()
        preparedAudioFiles.clear()
        preparedChunks.clear()
        dubbedAudioPlayer.stop()
        spokenAsrKey = ""
        translator?.close()
        translator = null
        translatorSourceLanguage = ""
        if (this::renderTts.isInitialized) renderTts.stop()
        startAsr()
    }

    private fun disable() {
        if (!enabled && !mutableState.value.requiresSetup) {
            mutableState.value = DubbingUiState()
            return
        }
        enabled = false
        usingAsr = false
        cueJob?.cancel()
        cueJob = null
        asrJob?.cancel()
        asrJob = null
        asrLoadJobs.values.forEach { it.cancel() }
        asrLoadJobs.clear()
        asrRenderJobs.values.forEach { it.cancel() }
        asrRenderJobs.clear()
        renderWaiters.values.forEach { it.cancel() }
        renderWaiters.clear()
        asrChunks.clear()
        preparedAudioFiles.clear()
        preparedChunks.clear()
        dubbedAudioPlayer.stop()
        dubbedAudioPlayer.clearMediaItems()
        if (this::liveTts.isInitialized) liveTts.stop()
        if (this::renderTts.isInitialized) renderTts.stop()
        player.volume = originalVolume
        originalTrackParameters?.let { player.trackSelectionParameters = it }
        originalTrackParameters = null
        lastCueText = ""
        spokenAsrKey = ""
        activeMediaKey = ""
        mutableState.value = DubbingUiState()
    }

    private fun startAsr() {
        val anchor = player.currentPosition.coerceAtLeast(0L)
        val resumePlayback = player.isPlaying || player.playWhenReady
        player.pause()
        asrJob?.cancel()
        asrJob = scope.launch {
            try {
                val initialStart = chunkStartFor(anchor)
                val segments = loadAsrChunk(initialStart, announce = true)
                if (!enabled || !usingAsr) return@launch
                val initialWindow = segments.filter {
                    it.endMs >= anchor && it.startMs <= anchor + INITIAL_RENDER_AHEAD_MS
                }
                if (initialWindow.isNotEmpty()) prepareAsrSegments(initialWindow, announce = true)
                queueRenderChunk(initialStart, segments, announce = false)
                if (!enabled || !usingAsr) return@launch
                player.seekTo(anchor)
                if (resumePlayback) player.play()
                runAsrScheduler()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (enabled) {
                    mutableState.value = mutableState.value.copy(
                        phase = DubbingPhase.ERROR,
                        message = "RU AI ASR: ${error.message ?: error.javaClass.simpleName}",
                    )
                    if (resumePlayback) player.play()
                }
            }
        }
    }

    private suspend fun runAsrScheduler() {
        while (scope.isActive && enabled && usingAsr) {
            val position = player.currentPosition.coerceAtLeast(0L)
            if (abs(position - lastPlayerPositionMs) > SEEK_RESET_THRESHOLD_MS) {
                dubbedAudioPlayer.stop()
                spokenAsrKey = ""
            }
            lastPlayerPositionMs = position

            val chunkStart = chunkStartFor(position)
            val segments = asrChunks[chunkStart]
            if (segments == null && asrLoadJobs[chunkStart]?.isActive != true) {
                queueAsrChunk(chunkStart, announce = true)
            } else if (segments != null) {
                queueRenderChunk(chunkStart, segments, announce = false)
                if (player.isPlaying) {
                    val segment = segments.firstOrNull {
                        position >= (it.startMs - SEGMENT_EARLY_WINDOW_MS).coerceAtLeast(0L) &&
                            position <= it.endMs + SEGMENT_LATE_WINDOW_MS
                    }
                    if (segment != null) {
                        val key = segmentKey(segment)
                        if (key != spokenAsrKey) {
                            spokenAsrKey = key
                            processAsrSegment(segment, key)
                        }
                    }
                }
            }

            val offset = position - chunkStart
            if (offset >= CHUNK_MS - PREFETCH_BEFORE_END_MS) {
                queueAsrChunk(chunkStart + CHUNK_MS, announce = false)
            }
            delay(SCHEDULER_TICK_MS)
        }
    }

    private fun queueAsrChunk(startMs: Long, announce: Boolean) {
        if (startMs < 0L || asrChunks.containsKey(startMs) || asrLoadJobs[startMs]?.isActive == true) return
        val job = scope.launch {
            try {
                val segments = loadAsrChunk(startMs, announce)
                if (segments.isNotEmpty()) queueRenderChunk(startMs, segments, announce = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (enabled && announce) {
                    mutableState.value = mutableState.value.copy(
                        phase = DubbingPhase.ERROR,
                        message = "RU AI ASR: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            } finally {
                asrLoadJobs.remove(startMs)
            }
        }
        asrLoadJobs[startMs] = job
    }

    private suspend fun loadAsrChunk(startMs: Long, announce: Boolean): List<AsrSegment> {
        val source = player.currentMediaItem ?: error("ASR_MEDIA_ITEM_MISSING")
        val mediaKey = source.localConfiguration?.uri?.toString().orEmpty()
        if (mediaKey.isBlank()) error("ASR_MEDIA_URI_MISSING")
        activeMediaKey = mediaKey
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        if (duration != null && startMs >= duration) return emptyList()
        val endMs = if (duration != null) (startMs + CHUNK_MS).coerceAtMost(duration) else startMs + CHUNK_MS
        if (endMs <= startMs) return emptyList()

        val config = preferences.load()
        activeConfig = config
        transcriptCache.load(mediaKey, startMs, endMs, config.model)?.let { cached ->
            asrChunks[startMs] = cached
            return cached
        }

        if (announce) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = "RU AI • SNP ASR ${formatClock(startMs)}–${formatClock(endMs)}…",
                source = DubbingSource.ASR,
            )
        }

        val audio = audioExporter.exportChunk(source, startMs, endMs)
        val relative = try {
            asrClient.transcribe(
                audio = audio,
                config = config,
                apiKey = preferences.apiKey(),
                chunkDurationMs = endMs - startMs,
            )
        } finally {
            audio.delete()
        }
        val absolute = relative.map { segment ->
            segment.copy(
                startMs = startMs + segment.startMs,
                endMs = (startMs + segment.endMs).coerceAtMost(endMs),
            )
        }.filter { it.text.isNotBlank() }

        if (absolute.isEmpty()) error("ASR_EMPTY_TRANSCRIPT")
        transcriptCache.save(mediaKey, startMs, endMs, config.model, absolute)
        asrChunks[startMs] = absolute
        if (announce && enabled) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = "RU AI • ASR готов, синтезирую русскую дорожку…",
                source = DubbingSource.ASR,
            )
        }
        return absolute
    }

    private fun queueRenderChunk(startMs: Long, segments: List<AsrSegment>, announce: Boolean) {
        if (segments.isEmpty() || startMs in preparedChunks || asrRenderJobs[startMs]?.isActive == true) return
        val job = scope.launch {
            try {
                prepareAsrSegments(segments, announce)
                preparedChunks += startMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Per-segment live TTS remains the fallback if pre-rendering is unavailable.
            } finally {
                asrRenderJobs.remove(startMs)
            }
        }
        asrRenderJobs[startMs] = job
    }

    private suspend fun prepareAsrSegments(segments: List<AsrSegment>, announce: Boolean): Int {
        if (segments.isEmpty()) return 0
        awaitRenderTtsReady()
        if (announce && enabled) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = "RU AI • готовлю синхронные русские реплики…",
                source = DubbingSource.ASR,
            )
        }
        var readyCount = 0
        for (segment in segments) {
            if (!enabled || !usingAsr) break
            val key = segmentKey(segment)
            val current = preparedAudioFiles[key]
            if (current?.isFile == true) {
                readyCount++
                continue
            }
            val rendered = runCatching { renderAsrSegment(segment) }.getOrNull()
            if (rendered != null) {
                preparedAudioFiles[key] = rendered
                readyCount++
            }
        }
        if (announce && enabled) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.ACTIVE,
                message = if (readyCount > 0) {
                    "RU AI • синхронная русская дорожка готова"
                } else {
                    "RU AI • live TTS fallback"
                },
                source = DubbingSource.ASR,
            )
        }
        return readyCount
    }

    private suspend fun renderAsrSegment(segment: AsrSegment): File? = renderMutex.withLock {
        if (!enabled || !usingAsr) return@withLock null
        awaitRenderTtsReady()
        val mediaKey = activeMediaKey.ifBlank {
            player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        }
        if (mediaKey.isBlank()) return@withLock null
        val voice = selectVoice(renderTts, segment.speaker) ?: return@withLock null
        val target = audioCache.targetFile(mediaKey, activeConfig.model, voice.name, segment)
        audioCache.existing(target)?.let { return@withLock it }

        val russian = translateToRussian(segment.text)
        if (russian.isBlank()) return@withLock null
        val segmentDurationMs = (segment.endMs - segment.startMs).coerceAtLeast(400L)
        val estimatedMs = (russian.length * APPROX_MS_PER_CHARACTER).toFloat()
        val fitRate = (estimatedMs / segmentDurationMs.toFloat()).coerceIn(0.85f, 1.55f)
        renderTts.voice = voice
        renderTts.setSpeechRate(fitRate.coerceIn(0.7f, 1.7f))

        val temp = audioCache.tempFile(target)
        val utteranceId = "noodle-render-${System.nanoTime()}"
        val waiter = CompletableDeferred<Unit>()
        renderWaiters[utteranceId] = waiter
        try {
            val result = renderTts.synthesizeToFile(russian, Bundle(), temp, utteranceId)
            if (result != TextToSpeech.SUCCESS) error("RU_TTS_RENDER_START_FAILED")
            withTimeout(TTS_RENDER_TIMEOUT_MS) { waiter.await() }
            val committed = audioCache.commit(temp, target)
            withContext(Dispatchers.IO) { audioCache.prune() }
            committed
        } finally {
            renderWaiters.remove(utteranceId)
            if (temp.exists()) temp.delete()
        }
    }

    private suspend fun awaitRenderTtsReady() {
        if (renderTtsReady) return
        withTimeout(TTS_INIT_TIMEOUT_MS) {
            while (!renderTtsReady) {
                if (closed) error("RU_TTS_CLOSED")
                delay(50L)
            }
        }
    }

    private fun processSubtitleCue(text: String) {
        lastCueText = text
        cueJob?.cancel()
        cueJob = scope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    enabled = true,
                    phase = DubbingPhase.PREPARING,
                    message = if (translatorSourceLanguage.isBlank()) "RU AI • определяю язык…" else "RU AI • перевод…",
                )
                val russian = translateToRussian(text)
                if (!enabled || usingAsr || lastCueText != text || !player.isPlaying) return@launch
                speakLive(
                    russian = russian,
                    speaker = "",
                    segmentDurationMs = 0L,
                    activeMessage = if (activeConfig.mode == DubbingMode.DUB) "RU AI • дубляж" else "RU AI • закадровый перевод",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSpeechError(error)
            }
        }
    }

    private fun processAsrSegment(segment: AsrSegment, expectedKey: String) {
        val prepared = preparedAudioFiles[expectedKey]?.takeIf { it.isFile }
        if (prepared != null) {
            playPreparedSegment(segment, expectedKey, prepared)
            return
        }

        cueJob?.cancel()
        cueJob = scope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    phase = DubbingPhase.PREPARING,
                    message = "RU AI • live fallback${speakerSuffix(segment.speaker)}…",
                    source = DubbingSource.ASR,
                )
                val russian = translateToRussian(segment.text)
                if (!enabled || !usingAsr || spokenAsrKey != expectedKey || !player.isPlaying) return@launch
                speakLive(
                    russian = russian,
                    speaker = segment.speaker,
                    segmentDurationMs = segment.endMs - segment.startMs,
                    activeMessage = if (activeConfig.mode == DubbingMode.DUB) {
                        "RU AI • дубляж${speakerSuffix(segment.speaker)}"
                    } else {
                        "RU AI • закадровый${speakerSuffix(segment.speaker)}"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSpeechError(error)
            }
        }
    }

    private fun playPreparedSegment(segment: AsrSegment, expectedKey: String, file: File) {
        if (!enabled || !usingAsr || spokenAsrKey != expectedKey || !player.isPlaying) return
        val offsetMs = (player.currentPosition - segment.startMs)
            .coerceAtLeast(0L)
            .coerceAtMost((segment.endMs - segment.startMs).coerceAtLeast(0L))
        dubbedAudioPlayer.stop()
        dubbedAudioPlayer.playbackParameters = player.playbackParameters
        dubbedAudioPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), offsetMs)
        dubbedAudioPlayer.prepare()
        dubbedAudioPlayer.playWhenReady = true
        mutableState.value = mutableState.value.copy(
            enabled = true,
            phase = DubbingPhase.ACTIVE,
            message = if (activeConfig.mode == DubbingMode.DUB) {
                "RU AI • синхронный дубляж${speakerSuffix(segment.speaker)}"
            } else {
                "RU AI • синхронный закадровый${speakerSuffix(segment.speaker)}"
            },
            sourceLanguage = translatorSourceLanguage,
            source = DubbingSource.ASR,
        )
    }

    private fun speakLive(
        russian: String,
        speaker: String,
        segmentDurationMs: Long,
        activeMessage: String,
    ) {
        if (!liveTtsReady) {
            showTtsUnavailable()
            return
        }
        applyConfiguredVoice(speaker)
        val playbackRate = player.playbackParameters.speed.coerceIn(0.75f, 1.5f)
        val fitRate = if (segmentDurationMs > 0L) {
            val estimatedMs = (russian.length * APPROX_MS_PER_CHARACTER).toFloat()
            (estimatedMs / segmentDurationMs.toFloat()).coerceIn(0.85f, 1.55f)
        } else 1f
        liveTts.setSpeechRate((playbackRate * fitRate).coerceIn(0.7f, 1.7f))
        val result = liveTts.speak(
            russian,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "noodle-ru-${System.nanoTime()}",
        )
        if (result == TextToSpeech.SUCCESS) {
            mutableState.value = mutableState.value.copy(
                enabled = true,
                phase = DubbingPhase.ACTIVE,
                message = activeMessage,
                sourceLanguage = translatorSourceLanguage,
                source = if (usingAsr) DubbingSource.ASR else DubbingSource.SUBTITLES,
            )
        } else {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.ERROR,
                message = "Ошибка русской озвучки",
            )
        }
    }

    private fun russianVoices(engine: TextToSpeech): List<Voice> =
        engine.voices.orEmpty()
            .filter { it.locale?.language.equals("ru", ignoreCase = true) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))

    private fun selectVoice(engine: TextToSpeech, speaker: String): Voice? {
        val voices = russianVoices(engine)
        if (voices.isEmpty()) return null
        return activeConfig.voiceName.takeIf { it.isNotBlank() }
            ?.let { name -> voices.firstOrNull { it.name == name } }
            ?: if (speaker.isNotBlank() && voices.size > 1) {
                voices[(speaker.hashCode() and Int.MAX_VALUE) % voices.size]
            } else voices.first()
    }

    private fun applyConfiguredVoice(speaker: String) {
        if (!this::liveTts.isInitialized || !liveTtsReady) return
        selectVoice(liveTts, speaker)?.let { liveTts.voice = it }
    }

    private suspend fun translateToRussian(text: String): String = translationMutex.withLock {
        if (translatorSourceLanguage.isBlank()) {
            val identified = runCatching { languageIdentifier.identifyLanguage(text).awaitTask() }
                .getOrDefault("und")
            translatorSourceLanguage = identified.takeUnless { it == "und" }.orEmpty()
                .ifBlank { TranslateLanguage.ENGLISH }
        }
        val source = TranslateLanguage.fromLanguageTag(translatorSourceLanguage) ?: TranslateLanguage.ENGLISH
        translatorSourceLanguage = source
        if (source == TranslateLanguage.RUSSIAN) return@withLock text

        var activeTranslator = translator
        if (activeTranslator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
            activeTranslator = Translation.getClient(options)
            translator = activeTranslator
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = "RU AI • загрузка модели $source→ru…",
                sourceLanguage = source,
            )
            activeTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
        }
        activeTranslator.translate(text).awaitTask().cleanCueText()
    }

    private fun showTtsUnavailable() {
        mutableState.value = mutableState.value.copy(
            enabled = enabled,
            phase = DubbingPhase.ERROR,
            message = "Русский голос TTS недоступен",
        )
    }

    private fun showSpeechError(error: Exception) {
        if (enabled) {
            mutableState.value = mutableState.value.copy(
                enabled = true,
                phase = DubbingPhase.ERROR,
                message = "RU AI: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun targetOriginalVolume(mode: DubbingMode): Float =
        if (mode == DubbingMode.DUB) DUB_ORIGINAL_VOLUME else VOICE_OVER_ORIGINAL_VOLUME

    private fun chunkStartFor(positionMs: Long): Long =
        (positionMs.coerceAtLeast(0L) / CHUNK_MS) * CHUNK_MS

    private fun segmentKey(segment: AsrSegment): String =
        "${segment.startMs}|${segment.endMs}|${segment.speaker}|${segment.text}"

    private fun speakerSuffix(speaker: String): String =
        speaker.takeIf { it.isNotBlank() }?.let { " • спикер $it" }.orEmpty()

    override fun close() {
        if (closed) return
        disable()
        closed = true
        player.removeListener(listener)
        translator?.close()
        translator = null
        languageIdentifier.close()
        dubbedAudioPlayer.release()
        if (this::liveTts.isInitialized) {
            liveTts.stop()
            liveTts.shutdown()
        }
        if (this::renderTts.isInitialized) {
            renderTts.stop()
            renderTts.shutdown()
        }
        scope.cancel()
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    private fun String.cleanCueText(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\{[^}]+}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun formatClock(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val minutes = total / 60L
        val seconds = total % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private companion object {
        const val VOICE_OVER_ORIGINAL_VOLUME = 0.22f
        const val DUB_ORIGINAL_VOLUME = 0.05f
        const val CHUNK_MS = 60_000L
        const val INITIAL_RENDER_AHEAD_MS = 20_000L
        const val PREFETCH_BEFORE_END_MS = 18_000L
        const val SCHEDULER_TICK_MS = 120L
        const val SEEK_RESET_THRESHOLD_MS = 2_500L
        const val SEGMENT_EARLY_WINDOW_MS = 140L
        const val SEGMENT_LATE_WINDOW_MS = 450L
        const val APPROX_MS_PER_CHARACTER = 62L
        const val TTS_INIT_TIMEOUT_MS = 8_000L
        const val TTS_RENDER_TIMEOUT_MS = 45_000L
        const val MAX_VOICE_CHOICES = 8
    }
}
