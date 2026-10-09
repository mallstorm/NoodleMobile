package pro.noodle.mobile.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
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
 * Russian voice-over synchronized to the Media3 playback clock.
 *
 * Fast path: existing subtitle cues -> on-device ML Kit translation -> Android TTS.
 * Fallback: when no subtitle track exists, Media3 Transformer extracts short audio chunks,
 * a user-configured OpenAI-compatible transcription endpoint returns timed speech segments,
 * and the same translation/TTS path follows those segment timestamps.
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

    private var translator: Translator? = null
    private var translatorSourceLanguage: String = ""
    private var cueJob: Job? = null
    private var asrJob: Job? = null
    private val asrLoadJobs = mutableMapOf<Long, Job>()
    private val asrChunks = mutableMapOf<Long, List<AsrSegment>>()

    private var enabled = false
    private var usingAsr = false
    private var ttsReady = false
    private var closed = false
    private var lastCueText = ""
    private var spokenAsrKey = ""
    private var lastPlayerPositionMs = 0L
    private var originalVolume = 1f
    private var originalTrackParameters: TrackSelectionParameters? = null
    private var activeConfig = preferences.load()
    private lateinit var tts: TextToSpeech

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
            if (!isPlaying && this@RussianDubbingController::tts.isInitialized) {
                tts.stop()
                if (usingAsr) spokenAsrKey = ""
            }
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            scope.launch {
                if (closed) return@launch
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val result = tts.setLanguage(Locale("ru", "RU"))
                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    tts.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    applyConfiguredVoice("")
                }
                if (enabled && !ttsReady) {
                    mutableState.value = mutableState.value.copy(
                        enabled = true,
                        phase = DubbingPhase.ERROR,
                        message = "Русский голос TTS недоступен",
                    )
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = Unit
        })
        player.addListener(listener)
    }

    fun toggle() {
        if (enabled) disable() else enable()
    }

    fun configuration(): DubbingConfig = preferences.load()

    fun hasAsrApiKey(): Boolean = preferences.hasApiKey()

    fun russianVoiceNames(): List<String> {
        if (!this::tts.isInitialized || !ttsReady) return emptyList()
        return tts.voices.orEmpty()
            .filter { it.locale?.language.equals("ru", ignoreCase = true) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            .map { it.name }
            .distinct()
            .take(MAX_VOICE_CHOICES)
    }

    fun saveConfiguration(config: DubbingConfig, newApiKey: String? = null) {
        preferences.save(config, newApiKey)
        activeConfig = preferences.load()
        if (enabled) {
            player.volume = targetOriginalVolume(activeConfig.mode)
            applyConfiguredVoice("")
        }
        if (mutableState.value.requiresSetup && preferences.hasApiKey()) {
            mutableState.value = mutableState.value.copy(requiresSetup = false)
            enable()
        }
    }

    private fun enable() {
        if (closed || enabled) return
        activeConfig = preferences.load()
        val textGroup = player.currentTracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSupported(it) }
        }
        if (textGroup != null) {
            enableSubtitleMode(textGroup)
        } else {
            enableAsrMode()
        }
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
            message = "RU AI • готовлю распознавание…",
            source = DubbingSource.ASR,
        )
        startAsr()
    }

    private fun rememberPlaybackState() {
        originalVolume = player.volume
        originalTrackParameters = player.trackSelectionParameters
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
        asrChunks.clear()
        if (this::tts.isInitialized) tts.stop()
        player.volume = originalVolume
        originalTrackParameters?.let { player.trackSelectionParameters = it }
        originalTrackParameters = null
        lastCueText = ""
        spokenAsrKey = ""
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
                loadAsrChunk(initialStart, announce = true)
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
                if (this::tts.isInitialized) tts.stop()
                spokenAsrKey = ""
            }
            lastPlayerPositionMs = position

            val chunkStart = chunkStartFor(position)
            val segments = asrChunks[chunkStart]
            if (segments == null && asrLoadJobs[chunkStart]?.isActive != true) {
                queueAsrChunk(chunkStart, announce = true)
            } else if (segments != null && player.isPlaying) {
                val segment = segments.firstOrNull {
                    position >= (it.startMs - SEGMENT_EARLY_WINDOW_MS).coerceAtLeast(0L) &&
                        position <= it.endMs + SEGMENT_LATE_WINDOW_MS
                }
                if (segment != null) {
                    val key = "${segment.startMs}|${segment.endMs}|${segment.speaker}|${segment.text}"
                    if (key != spokenAsrKey) {
                        spokenAsrKey = key
                        processAsrSegment(segment, key)
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
                loadAsrChunk(startMs, announce)
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
                message = "RU AI • ASR ${formatClock(startMs)}–${formatClock(endMs)}…",
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
                phase = DubbingPhase.ACTIVE,
                message = "RU AI • ASR готов",
                source = DubbingSource.ASR,
            )
        }
        return absolute
    }

    private fun processSubtitleCue(text: String) {
        lastCueText = text
        cueJob?.cancel()
        cueJob = scope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    enabled = true,
                    phase = DubbingPhase.PREPARING,
                    message = if (translatorSourceLanguage.isBlank()) {
                        "RU AI • определяю язык…"
                    } else {
                        "RU AI • перевод…"
                    },
                )
                val russian = translateToRussian(text)
                if (!enabled || usingAsr || lastCueText != text || !player.isPlaying) return@launch
                speak(
                    russian = russian,
                    speaker = "",
                    segmentDurationMs = 0L,
                    activeMessage = if (activeConfig.mode == DubbingMode.DUB) {
                        "RU AI • дубляж"
                    } else {
                        "RU AI • закадровый перевод"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSpeechError(error)
            }
        }
    }

    private fun processAsrSegment(segment: AsrSegment, expectedKey: String) {
        cueJob?.cancel()
        cueJob = scope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    phase = DubbingPhase.PREPARING,
                    message = "RU AI • перевод${segment.speaker.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()}…",
                    source = DubbingSource.ASR,
                )
                val russian = translateToRussian(segment.text)
                if (!enabled || !usingAsr || spokenAsrKey != expectedKey || !player.isPlaying) return@launch
                val label = segment.speaker.takeIf { it.isNotBlank() }?.let { " • спикер $it" }.orEmpty()
                speak(
                    russian = russian,
                    speaker = segment.speaker,
                    segmentDurationMs = segment.endMs - segment.startMs,
                    activeMessage = if (activeConfig.mode == DubbingMode.DUB) {
                        "RU AI • дубляж$label"
                    } else {
                        "RU AI • закадровый$label"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSpeechError(error)
            }
        }
    }

    private fun speak(
        russian: String,
        speaker: String,
        segmentDurationMs: Long,
        activeMessage: String,
    ) {
        if (!ttsReady) {
            mutableState.value = mutableState.value.copy(
                phase = DubbingPhase.ERROR,
                message = "Русский голос TTS недоступен",
            )
            return
        }
        applyConfiguredVoice(speaker)
        val playbackRate = player.playbackParameters.speed.coerceIn(0.75f, 1.5f)
        val fitRate = if (segmentDurationMs > 0L) {
            val estimatedMs = (russian.length * APPROX_MS_PER_CHARACTER).toFloat()
            (estimatedMs / segmentDurationMs.toFloat()).coerceIn(0.85f, 1.55f)
        } else {
            1f
        }
        tts.setSpeechRate((playbackRate * fitRate).coerceIn(0.7f, 1.7f))
        val result = tts.speak(
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

    private fun applyConfiguredVoice(speaker: String) {
        if (!this::tts.isInitialized || !ttsReady) return
        val voices = tts.voices.orEmpty()
            .filter { it.locale?.language.equals("ru", ignoreCase = true) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
        if (voices.isEmpty()) return

        val selected = activeConfig.voiceName.takeIf { it.isNotBlank() }
            ?.let { name -> voices.firstOrNull { it.name == name } }
            ?: if (speaker.isNotBlank() && voices.size > 1) {
                voices[(speaker.hashCode() and Int.MAX_VALUE) % voices.size]
            } else {
                voices.first()
            }
        tts.voice = selected
    }

    private suspend fun translateToRussian(text: String): String {
        if (translatorSourceLanguage.isBlank()) {
            val identified = runCatching { languageIdentifier.identifyLanguage(text).awaitTask() }
                .getOrDefault("und")
            translatorSourceLanguage = identified.takeUnless { it == "und" }.orEmpty()
                .ifBlank { TranslateLanguage.ENGLISH }
        }
        val source = TranslateLanguage.fromLanguageTag(translatorSourceLanguage)
            ?: TranslateLanguage.ENGLISH
        translatorSourceLanguage = source
        if (source == TranslateLanguage.RUSSIAN) return text

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
        return activeTranslator.translate(text).awaitTask().cleanCueText()
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

    override fun close() {
        if (closed) return
        disable()
        closed = true
        player.removeListener(listener)
        translator?.close()
        translator = null
        languageIdentifier.close()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
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
        const val PREFETCH_BEFORE_END_MS = 18_000L
        const val SCHEDULER_TICK_MS = 120L
        const val SEEK_RESET_THRESHOLD_MS = 2_500L
        const val SEGMENT_EARLY_WINDOW_MS = 140L
        const val SEGMENT_LATE_WINDOW_MS = 450L
        const val APPROX_MS_PER_CHARACTER = 62L
        const val MAX_VOICE_CHOICES = 8
    }
}
