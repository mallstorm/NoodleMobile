package pro.noodle.mobile.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
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

internal data class DubbingUiState(
    val enabled: Boolean = false,
    val phase: DubbingPhase = DubbingPhase.OFF,
    val message: String = "RU AI",
    val sourceLanguage: String = "",
    val requiresSetup: Boolean = false,
)

/**
 * Speech-first Russian auto-dubbing synchronized to the Media3 playback clock.
 *
 * Audio is always the source. ASR produces timed/speaker-aware segments, ML Kit translates them,
 * Android TTS pre-renders Russian speech and [DubbingTimelineAssembler] places speech on canonical
 * PCM timeline chunks. Ready adjacent chunks are queued on one Media3 playlist so 20-second window
 * boundaries are gapless. The secondary clock is continuously compared with the main video clock,
 * while prepared-segment/live TTS remains a low-latency fallback when a future chunk is not ready.
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
    private val timelineAssembler = DubbingTimelineAssembler(appContext)
    private val dubbedAudioPlayer = ExoPlayer.Builder(appContext).build()
    private val translationMutex = Mutex()
    private val renderMutex = Mutex()
    private val renderWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private var translator: Translator? = null
    private var translatorSourceLanguage = ""
    private var cueJob: Job? = null
    private var asrJob: Job? = null
    private val asrLoadJobs = mutableMapOf<Long, Job>()
    private val asrRenderJobs = mutableMapOf<Long, Job>()
    private val asrChunks = mutableMapOf<Long, List<AsrSegment>>()
    private val preparedAudioFiles = mutableMapOf<String, File>()
    private val preparedTimelineChunks = mutableMapOf<Long, File>()
    private val preparedChunks = mutableSetOf<Long>()
    private val timelinePlaylistChunks = mutableListOf<Long>()

    private var enabled = false
    private var usingAsr = false
    private var liveTtsReady = false
    private var renderTtsReady = false
    private var closed = false
    private var spokenAsrKey = ""
    private var lastPlayerPositionMs = 0L
    private var originalVolume = 1f
    private var activeConfig = preferences.load()
    private var activeMediaKey = ""
    private var activeTimelineChunkStart = NO_TIMELINE_CHUNK
    private lateinit var liveTts: TextToSpeech
    private lateinit var renderTts: TextToSpeech

    private val mutableState = MutableStateFlow(DubbingUiState())
    val state: StateFlow<DubbingUiState> = mutableState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                dubbedAudioPlayer.pause()
                if (this@RussianDubbingController::liveTts.isInitialized) liveTts.stop()
                spokenAsrKey = ""
                setIdleOriginalVolume()
            } else if (enabled) {
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
                if (enabled && !liveTtsReady) showTtsUnavailable()
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
            setIdleOriginalVolume()
            restartAsrPipeline()
        }
    }

    private fun configureRussianTts(
        engine: TextToSpeech,
        status: Int,
        setMediaAttributes: Boolean,
    ): Boolean {
        if (status != TextToSpeech.SUCCESS) return false
        val result = engine.setLanguage(Locale("ru", "RU"))
        val ready = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
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
        enableAsrMode()
    }

    private fun enableAsrMode() {
        if (!preferences.hasApiKey()) {
            mutableState.value = DubbingUiState(
                enabled = false,
                phase = DubbingPhase.ERROR,
                message = "RU AI • настройте ASR API",
                requiresSetup = true,
            )
            return
        }
        rememberPlaybackState()
        enabled = true
        usingAsr = true
        setIdleOriginalVolume()
        spokenAsrKey = ""
        lastPlayerPositionMs = player.currentPosition.coerceAtLeast(0L)
        mutableState.value = DubbingUiState(
            enabled = true,
            phase = DubbingPhase.PREPARING,
            message = "RU AI • слушаю речь и готовлю дубляж…",
        )
        startAsr()
    }

    private fun rememberPlaybackState() {
        originalVolume = player.volume
    }

    private fun restartAsrPipeline() {
        if (!enabled || !usingAsr) return
        cancelPipelineJobs()
        asrChunks.clear()
        preparedAudioFiles.clear()
        preparedTimelineChunks.clear()
        preparedChunks.clear()
        stopDubbedAudioPlayback()
        spokenAsrKey = ""
        translator?.close()
        translator = null
        translatorSourceLanguage = ""
        if (this::renderTts.isInitialized) renderTts.stop()
        startAsr()
    }

    private fun cancelPipelineJobs() {
        asrJob?.cancel()
        asrJob = null
        cueJob?.cancel()
        cueJob = null
        asrLoadJobs.values.forEach { it.cancel() }
        asrLoadJobs.clear()
        asrRenderJobs.values.forEach { it.cancel() }
        asrRenderJobs.clear()
        renderWaiters.values.forEach { it.cancel() }
        renderWaiters.clear()
    }

    private fun disable() {
        if (!enabled && !mutableState.value.requiresSetup) {
            mutableState.value = DubbingUiState()
            return
        }
        enabled = false
        usingAsr = false
        cancelPipelineJobs()
        asrChunks.clear()
        preparedAudioFiles.clear()
        preparedTimelineChunks.clear()
        preparedChunks.clear()
        stopDubbedAudioPlayback()
        if (this::liveTts.isInitialized) liveTts.stop()
        if (this::renderTts.isInitialized) renderTts.stop()
        player.volume = originalVolume
        spokenAsrKey = ""
        activeMediaKey = ""
        translator?.close()
        translator = null
        translatorSourceLanguage = ""
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
                if (initialWindow.isNotEmpty()) {
                    prepareAsrSegments(initialWindow, announce = true)
                }
                queueRenderChunk(initialStart, segments, announce = false)

                if (!enabled || !usingAsr) return@launch
                player.seekTo(anchor)
                if (resumePlayback) player.play()

                // Give the next window a complete chunk of lead time before the first boundary.
                queueAsrChunk(initialStart + CHUNK_MS, announce = false)
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
                stopDubbedAudioPlayback()
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
                    val activeSegment = segments.firstOrNull {
                        position >= (it.startMs - SEGMENT_EARLY_WINDOW_MS).coerceAtLeast(0L) &&
                            position <= it.endMs + SEGMENT_LATE_WINDOW_MS
                    }
                    val timeline = preparedTimelineChunks[chunkStart]?.takeIf { it.isFile }

                    if (timeline != null) {
                        playOrSyncTimelinePlaylist(chunkStart, timeline, position)
                        if (activeSegment != null) {
                            setSpeechOriginalVolume()
                        } else {
                            setIdleOriginalVolume()
                        }
                    } else {
                        if (timelinePlaylistChunks.isNotEmpty()) {
                            stopDubbedAudioPlayback()
                        }
                        if (activeSegment != null) {
                            setSpeechOriginalVolume()
                            val key = segmentKey(activeSegment)
                            if (key != spokenAsrKey) {
                                spokenAsrKey = key
                                processAsrSegment(activeSegment, key)
                            }
                        } else {
                            setIdleOriginalVolume()
                            if (dubbedAudioPlayer.isPlaying || dubbedAudioPlayer.mediaItemCount > 0) {
                                stopDubbedAudioPlayback()
                            }
                            if (this::liveTts.isInitialized) liveTts.stop()
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
        if (startMs < 0L || asrChunks.containsKey(startMs) || asrLoadJobs[startMs]?.isActive == true) {
            return
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        if (duration != null && startMs >= duration) return

        val job = scope.launch {
            try {
                val segments = loadAsrChunk(startMs, announce)
                queueRenderChunk(startMs, segments, announce = false)
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
        val endMs = if (duration != null) {
            (startMs + CHUNK_MS).coerceAtMost(duration)
        } else {
            startMs + CHUNK_MS
        }
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

        // An empty ASR result is a valid silence window. Cache it so the same 20 seconds are not
        // retranscribed and so the timeline assembler can bridge speech before/after it gaplessly.
        transcriptCache.save(mediaKey, startMs, endMs, config.model, absolute)
        asrChunks[startMs] = absolute

        if (announce && enabled) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = if (absolute.isEmpty()) {
                    "RU AI • тишина, продолжаю дорожку…"
                } else {
                    "RU AI • ASR готов, синтезирую русскую дорожку…"
                },
            )
        }
        return absolute
    }

    private fun queueRenderChunk(startMs: Long, segments: List<AsrSegment>, announce: Boolean) {
        if (startMs in preparedChunks || asrRenderJobs[startMs]?.isActive == true) return
        val job = scope.launch {
            try {
                val readyCount = prepareAsrSegments(segments, announce)
                if (!enabled || !usingAsr) return@launch

                if (readyCount == segments.size) {
                    val timeline = buildTimelineChunk(startMs, segments)
                    preparedTimelineChunks[startMs] = timeline
                    appendReadyTimelineChunks()
                    mutableState.value = mutableState.value.copy(
                        phase = DubbingPhase.ACTIVE,
                        message = if (segments.isEmpty()) {
                            "RU AI • gapless дорожка • тишина"
                        } else {
                            "RU AI • gapless русская дорожка готова"
                        },
                    )
                }
                // One complete render pass is enough. Failed speech files retain the proven
                // prepared-segment/live fallback instead of retriggering work every scheduler tick.
                preparedChunks += startMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preparedChunks += startMs
            } finally {
                asrRenderJobs.remove(startMs)
            }
        }
        asrRenderJobs[startMs] = job
    }

    private suspend fun prepareAsrSegments(
        segments: List<AsrSegment>,
        announce: Boolean,
    ): Int {
        if (segments.isEmpty()) return 0
        awaitRenderTtsReady()
        if (announce && enabled) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.PREPARING,
                message = "RU AI • готовлю синхронные русские реплики…",
            )
        }

        var readyCount = 0
        for (segment in segments) {
            if (!enabled || !usingAsr) break
            val key = segmentKey(segment)
            val existing = preparedAudioFiles[key]
            if (existing?.isFile == true) {
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
                    "RU AI • русские реплики готовы, собираю дорожку…"
                } else {
                    "RU AI • live TTS fallback"
                },
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
        val russian = translateToRussian(segment.text)
        if (russian.isBlank()) return@withLock null

        val target = audioCache.targetFile(
            mediaKey = mediaKey,
            model = activeConfig.model,
            voiceName = voice.name,
            segment = segment,
            russianText = russian,
        )
        audioCache.existing(target)?.let { return@withLock it }

        val segmentDurationMs = (segment.endMs - segment.startMs).coerceAtLeast(400L)
        val estimatedMs = (russian.length * APPROX_MS_PER_CHARACTER).toFloat()
        val initialRate = (estimatedMs / segmentDurationMs.toFloat()).coerceIn(0.78f, 1.65f)
        val temp = audioCache.tempFile(target)
        try {
            renderSpeechToFile(russian, voice, initialRate, temp)
            val actualMs = audioDurationMs(temp)
            if (actualMs > 0L) {
                val ratio = actualMs.toFloat() / segmentDurationMs.toFloat()
                if (ratio !in TTS_DURATION_FIT_MIN..TTS_DURATION_FIT_MAX) {
                    val correctedRate = (initialRate * ratio)
                        .coerceIn(MIN_RENDER_SPEECH_RATE, MAX_RENDER_SPEECH_RATE)
                    if (abs(correctedRate - initialRate) >= MIN_RERENDER_RATE_DELTA) {
                        temp.delete()
                        renderSpeechToFile(russian, voice, correctedRate, temp)
                    }
                }
            }
            val committed = audioCache.commit(temp, target)
            withContext(Dispatchers.IO) { audioCache.prune() }
            committed
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private suspend fun renderSpeechToFile(
        russian: String,
        voice: Voice,
        speechRate: Float,
        output: File,
    ) {
        renderTts.voice = voice
        renderTts.setSpeechRate(speechRate)
        val utteranceId = "noodle-render-${System.nanoTime()}"
        val waiter = CompletableDeferred<Unit>()
        renderWaiters[utteranceId] = waiter
        try {
            val result = renderTts.synthesizeToFile(russian, Bundle(), output, utteranceId)
            if (result != TextToSpeech.SUCCESS) error("RU_TTS_RENDER_START_FAILED")
            withTimeout(TTS_RENDER_TIMEOUT_MS) { waiter.await() }
        } finally {
            renderWaiters.remove(utteranceId)
        }
    }

    private fun audioDurationMs(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    private suspend fun awaitRenderTtsReady() {
        if (renderTtsReady) return
        withTimeout(TTS_INIT_TIMEOUT_MS) {
            while (!renderTtsReady) {
                if (closed) error("RU_TTS_CLOSED")
                delay(50L)
            }
        }
    }

    private suspend fun buildTimelineChunk(startMs: Long, segments: List<AsrSegment>): File {
        val mediaKey = activeMediaKey.ifBlank {
            player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        }
        if (mediaKey.isBlank()) error("RU_TIMELINE_MEDIA_KEY_MISSING")

        val speech = segments.map { segment ->
            val file = preparedAudioFiles[segmentKey(segment)]?.takeIf { it.isFile }
                ?: error("RU_TIMELINE_SEGMENT_MISSING")
            DubbingTimelineAssembler.SpeechFile(segment, file)
        }
        val endMs = chunkEndFor(startMs)
        return withContext(Dispatchers.IO) {
            timelineAssembler.assemble(
                mediaKey = mediaKey,
                chunkStartMs = startMs,
                chunkEndMs = endMs,
                speech = speech,
            )
        }
    }

    private fun playOrSyncTimelinePlaylist(
        startMs: Long,
        file: File,
        mediaPositionMs: Long,
    ) {
        if (!enabled || !usingAsr || !player.isPlaying || !file.isFile) return

        val chunkEnd = chunkEndFor(startMs)
        val maxOffset = (chunkEnd - startMs - 1L).coerceAtLeast(0L)
        val expectedOffset = (mediaPositionMs - startMs).coerceIn(0L, maxOffset)
        val expectedIndex = timelinePlaylistChunks.indexOf(startMs)

        if (
            expectedIndex < 0 ||
            timelinePlaylistChunks.isEmpty() ||
            dubbedAudioPlayer.mediaItemCount != timelinePlaylistChunks.size
        ) {
            rebuildTimelinePlaylist(startMs, expectedOffset)
            return
        }

        appendReadyTimelineChunks()
        val refreshedExpectedIndex = timelinePlaylistChunks.indexOf(startMs)
        if (refreshedExpectedIndex < 0) {
            rebuildTimelinePlaylist(startMs, expectedOffset)
            return
        }

        val currentIndex = dubbedAudioPlayer.currentMediaItemIndex
        val currentChunkStart = timelinePlaylistChunks.getOrNull(currentIndex)
        if (currentChunkStart == null) {
            rebuildTimelinePlaylist(startMs, expectedOffset)
            return
        }

        val secondaryMediaPositionMs = currentChunkStart + dubbedAudioPlayer.currentPosition
        val driftMs = secondaryMediaPositionMs - mediaPositionMs
        if (abs(driftMs) >= TIMELINE_DRIFT_CORRECTION_MS) {
            dubbedAudioPlayer.seekTo(refreshedExpectedIndex, expectedOffset)
        }
        if (dubbedAudioPlayer.playbackState == Player.STATE_ENDED) {
            dubbedAudioPlayer.seekTo(refreshedExpectedIndex, expectedOffset)
            dubbedAudioPlayer.prepare()
        }
        if (!dubbedAudioPlayer.playWhenReady) dubbedAudioPlayer.playWhenReady = true

        activeTimelineChunkStart = startMs
        mutableState.value = mutableState.value.copy(
            enabled = true,
            phase = DubbingPhase.ACTIVE,
            message = if (activeConfig.mode == DubbingMode.DUB) {
                "RU AI • gapless синхронный дубляж"
            } else {
                "RU AI • gapless синхронный закадровый"
            },
            sourceLanguage = translatorSourceLanguage,
        )
    }

    private fun rebuildTimelinePlaylist(startMs: Long, expectedOffsetMs: Long) {
        val starts = mutableListOf<Long>()
        val items = mutableListOf<MediaItem>()
        var candidate = startMs
        while (true) {
            val candidateFile = preparedTimelineChunks[candidate]?.takeIf { it.isFile } ?: break
            starts += candidate
            items += timelineMediaItem(candidate, candidateFile)
            candidate += CHUNK_MS
        }
        if (items.isEmpty()) return

        cueJob?.cancel()
        cueJob = null
        if (this::liveTts.isInitialized) liveTts.stop()
        dubbedAudioPlayer.stop()
        dubbedAudioPlayer.clearMediaItems()
        timelinePlaylistChunks.clear()
        timelinePlaylistChunks.addAll(starts)
        dubbedAudioPlayer.playbackParameters = player.playbackParameters
        dubbedAudioPlayer.setMediaItems(items, 0, expectedOffsetMs)
        dubbedAudioPlayer.prepare()
        dubbedAudioPlayer.playWhenReady = true
        activeTimelineChunkStart = startMs
        spokenAsrKey = ""
    }

    private fun appendReadyTimelineChunks() {
        if (timelinePlaylistChunks.isEmpty()) return
        var candidate = timelinePlaylistChunks.last() + CHUNK_MS
        while (true) {
            val file = preparedTimelineChunks[candidate]?.takeIf { it.isFile } ?: break
            dubbedAudioPlayer.addMediaItem(timelineMediaItem(candidate, file))
            timelinePlaylistChunks += candidate
            candidate += CHUNK_MS
        }
    }

    private fun timelineMediaItem(startMs: Long, file: File): MediaItem =
        MediaItem.Builder()
            .setMediaId(timelineMediaId(startMs, file))
            .setUri(Uri.fromFile(file))
            .build()

    private fun timelineMediaId(startMs: Long, file: File): String =
        "ru-timeline:$startMs:${file.name}"

    private fun stopDubbedAudioPlayback() {
        dubbedAudioPlayer.stop()
        dubbedAudioPlayer.clearMediaItems()
        timelinePlaylistChunks.clear()
        activeTimelineChunkStart = NO_TIMELINE_CHUNK
    }

    private fun chunkEndFor(startMs: Long): Long {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        return if (duration != null) {
            (startMs + CHUNK_MS).coerceAtMost(duration)
        } else {
            startMs + CHUNK_MS
        }
    }

    private fun processAsrSegment(segment: AsrSegment, expectedKey: String) {
        setSpeechOriginalVolume()
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
                )
                val russian = translateToRussian(segment.text)
                if (!enabled || !usingAsr || spokenAsrKey != expectedKey || !player.isPlaying) {
                    return@launch
                }
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
        setSpeechOriginalVolume()
        stopDubbedAudioPlayback()
        val offsetMs = (player.currentPosition - segment.startMs)
            .coerceAtLeast(0L)
            .coerceAtMost((segment.endMs - segment.startMs).coerceAtLeast(0L))
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
        setSpeechOriginalVolume()
        stopDubbedAudioPlayback()
        applyConfiguredVoice(speaker)

        val playbackRate = player.playbackParameters.speed.coerceIn(0.75f, 1.5f)
        val fitRate = if (segmentDurationMs > 0L) {
            val estimatedMs = (russian.length * APPROX_MS_PER_CHARACTER).toFloat()
            (estimatedMs / segmentDurationMs.toFloat()).coerceIn(0.85f, 1.55f)
        } else {
            1f
        }
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
            } else {
                voices.first()
            }
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
        val source = TranslateLanguage.fromLanguageTag(translatorSourceLanguage)
            ?: TranslateLanguage.ENGLISH
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

    private fun setSpeechOriginalVolume() {
        if (!enabled) return
        player.volume = originalVolume.coerceAtMost(targetSpeechOriginalVolume(activeConfig.mode))
    }

    private fun setIdleOriginalVolume() {
        if (!enabled) return
        player.volume = originalVolume.coerceAtMost(targetIdleOriginalVolume(activeConfig.mode))
    }

    private fun targetSpeechOriginalVolume(mode: DubbingMode): Float =
        if (mode == DubbingMode.DUB) DUB_SPEECH_ORIGINAL_VOLUME
        else VOICE_OVER_SPEECH_ORIGINAL_VOLUME

    private fun targetIdleOriginalVolume(mode: DubbingMode): Float =
        if (mode == DubbingMode.DUB) DUB_IDLE_ORIGINAL_VOLUME
        else VOICE_OVER_IDLE_ORIGINAL_VOLUME

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
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }

    private fun String.cleanCueText(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\{[^}]+}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun formatClock(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1_000L
        val minutes = total / 60L
        val seconds = total % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private companion object {
        const val VOICE_OVER_SPEECH_ORIGINAL_VOLUME = 0.22f
        const val DUB_SPEECH_ORIGINAL_VOLUME = 0.05f
        const val VOICE_OVER_IDLE_ORIGINAL_VOLUME = 0.72f
        const val DUB_IDLE_ORIGINAL_VOLUME = 0.32f
        const val CHUNK_MS = 20_000L
        const val INITIAL_RENDER_AHEAD_MS = 8_000L
        const val PREFETCH_BEFORE_END_MS = 15_000L
        const val SCHEDULER_TICK_MS = 120L
        const val SEEK_RESET_THRESHOLD_MS = 2_500L
        const val SEGMENT_EARLY_WINDOW_MS = 140L
        const val SEGMENT_LATE_WINDOW_MS = 450L
        const val APPROX_MS_PER_CHARACTER = 62L
        const val TTS_INIT_TIMEOUT_MS = 8_000L
        const val TTS_RENDER_TIMEOUT_MS = 45_000L
        const val TTS_DURATION_FIT_MIN = 0.88f
        const val TTS_DURATION_FIT_MAX = 1.12f
        const val MIN_RENDER_SPEECH_RATE = 0.72f
        const val MAX_RENDER_SPEECH_RATE = 1.80f
        const val MIN_RERENDER_RATE_DELTA = 0.06f
        const val TIMELINE_DRIFT_CORRECTION_MS = 120L
        const val NO_TIMELINE_CHUNK = Long.MIN_VALUE
        const val MAX_VOICE_CHOICES = 8
    }
}
